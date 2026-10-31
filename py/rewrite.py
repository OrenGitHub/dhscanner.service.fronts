'''
Rewrite dotted 1st party accesses into `from` imports ( runner #131 ).

    import weblate.accounts.views
    path("hosting/", weblate.accounts.views.hosting, name="hosting")

becomes

    from weblate.accounts.views import hosting as dhscanner_instrumented_hosting
    import weblate.accounts.views
    path("hosting/", dhscanner_instrumented_hosting, name="hosting")

so the dhscanner parsers see a symbol imported from its exact file
( `weblate/accounts/views.py` ), a shape the rest of the pipeline already
resolves, instead of a dotted name that looks like a 3rd party package.

The scanned `.py` files decide where the module path ends : walk the dots,
the first `.py` file ends it ( a dir of the same name wins, like python ),
the next name is the symbol. The rest of the chain is kept as is :
`weblate.trans.views.about.StatsView.as_view()` -> `dhscanner_instrumented_StatsView.as_view()`.

A dotted access on a 1st party **class** goes through a local subclass,

    class dhscanner_instrumented_StatsView_Subclass(dhscanner_instrumented_StatsView):
        pass
    path("stats/", dhscanner_instrumented_StatsView_Subclass.as_view(), name="stats")

so the parsers see a dotted call on a class defined in this file : a method
of that class, whose 1st party super is `StatsView` from its exact file.

A bare reference to a 1st party **function** passed as a call argument is
called, with no arguments,

    path("hosting/", dhscanner_instrumented_hosting(), name="hosting")

so the parsers see a call to `hosting` from its exact file, a call graph
edge ( the django router does call the view ). Decorators and references
that are already called are left as is.

The top level class / function names per file come with the request.

A chain is rewritten only when its root is a name the file binds to a
scanned module : `import a.b.c` binds `a`, `import a.b.c as m` binds `m`,
`from a.b import c` binds `c` when `a/b/c.py` ( or a dir ) is scanned.
Every replacement keeps the location of the expression it replaces.
'''

from __future__ import annotations

import ast
import typing

PREFIX = 'dhscanner_instrumented_'
SUBCLASS_SUFFIX = '_Subclass'


class SourceTree:

    def __init__(
        self,
        files: typing.Iterable[str],
        classes: typing.Optional[dict[str, list[str]]] = None,
        functions: typing.Optional[dict[str, list[str]]] = None
    ) -> None:
        self.files = {f for f in files if f.endswith('.py')}
        self.classes = {f: set(names) for f, names in (classes or {}).items()}
        self.functions = {f: set(names) for f, names in (functions or {}).items()}
        self.dirs: set[str] = set()
        for f in self.files:
            parts = f.split('/')[:-1]
            for i in range(1, len(parts) + 1):
                self.dirs.add('/'.join(parts[:i]))

    def is_module(self, parts: list[str]) -> bool:
        path = '/'.join(parts)
        return path in self.dirs or f'{path}.py' in self.files

    def is_class(self, parts: list[str], symbol_index: int) -> bool:
        module_file = '/'.join(parts[:symbol_index]) + '.py'
        return parts[symbol_index] in self.classes.get(module_file, set())

    def is_function(self, parts: list[str], symbol_index: int) -> bool:
        module_file = '/'.join(parts[:symbol_index]) + '.py'
        return parts[symbol_index] in self.functions.get(module_file, set())

    def split(self, parts: list[str]) -> typing.Optional[int]:
        '''
        index of the symbol in `parts`, right after the first `.py` file :
        [ weblate, accounts, views, hosting ] -> 3
        '''
        for i in range(1, len(parts)):
            path = '/'.join(parts[:i])
            if path in self.dirs:
                continue
            if f'{path}.py' in self.files:
                return i
            return None
        return None


def _chain(node: ast.expr) -> typing.Optional[list[str]]:
    '''`a.b.c` -> [ a, b, c ], None unless the root is a plain name'''
    attrs: list[str] = []
    while isinstance(node, ast.Attribute):
        attrs.append(node.attr)
        node = node.value
    if isinstance(node, ast.Name):
        return [node.id] + attrs[::-1]
    return None


def _bindings(tree: ast.Module, source_tree: SourceTree) -> dict[str, list[str]]:
    bound: dict[str, list[str]] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for a in node.names:
                parts = a.name.split('.')
                if a.asname is None:
                    if source_tree.is_module(parts[:1]):
                        bound[parts[0]] = parts[:1]
                elif source_tree.is_module(parts):
                    bound[a.asname] = parts
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            for a in node.names:
                parts = node.module.split('.') + [a.name]
                if source_tree.is_module(parts):
                    bound[a.asname or a.name] = parts
    return bound


class _Rewriter(ast.NodeTransformer):

    def __init__(self, source_tree: SourceTree, bound: dict[str, list[str]]) -> None:
        self.source_tree = source_tree
        self.bound = bound
        # ( module, symbol ) -> alias, in first use order
        self.aliases: dict[tuple[str, str], str] = {}
        self.first_use: dict[tuple[str, str], ast.expr] = {}
        # ( module, symbol ) of the classes reached through a dotted access
        self.subclassed: set[tuple[str, str]] = set()

    def visit_Call(self, node: ast.Call) -> ast.AST:
        # positional args that are bare references to a 1st party function
        called = [i for i, arg in enumerate(node.args) if self._is_function_reference(arg)]
        node = typing.cast(ast.Call, self.generic_visit(node))
        for i in called:
            node.args[i] = ast.copy_location(ast.Call(func=node.args[i], args=[], keywords=[]), node.args[i])
        return node

    def _is_function_reference(self, node: ast.expr) -> bool:
        chain = _chain(node)
        if chain is None or len(chain) < 2 or chain[0] not in self.bound:
            return False
        parts = self.bound[chain[0]] + chain[1:]
        symbol_index = self.source_tree.split(parts)
        return symbol_index == len(parts) - 1 and self.source_tree.is_function(parts, symbol_index)

    def visit_Attribute(self, node: ast.Attribute) -> ast.AST:
        chain = _chain(node)
        if chain is not None and chain[0] in self.bound:
            parts = self.bound[chain[0]] + chain[1:]
            symbol_index = self.source_tree.split(parts)
            # a dotted access right on a class : `StatsView.as_view` -> `<alias>_Subclass.as_view`
            if symbol_index is not None and symbol_index == len(parts) - 2 and self.source_tree.is_class(parts, symbol_index):
                module = '.'.join(parts[:symbol_index])
                alias = self._alias(module, parts[symbol_index], node.value)
                self.subclassed.add((module, parts[symbol_index]))
                receiver = ast.copy_location(ast.Name(id=alias + SUBCLASS_SUFFIX, ctx=ast.Load()), node.value)
                return ast.copy_location(ast.Attribute(value=receiver, attr=node.attr, ctx=node.ctx), node)
            # replace exactly the sub chain that ends with the symbol, the
            # outer attributes ( `.as_view` ) are rebuilt by generic_visit
            if symbol_index is not None and symbol_index == len(parts) - 1:
                module = '.'.join(parts[:symbol_index])
                return ast.copy_location(ast.Name(id=self._alias(module, parts[symbol_index], node), ctx=node.ctx), node)
        return self.generic_visit(node)

    def _alias(self, module: str, symbol: str, node: ast.expr) -> str:
        key = (module, symbol)
        if key not in self.aliases:
            alias = PREFIX + symbol
            if alias in self.aliases.values():
                alias = f'{alias}__{module.replace(".", "_")}'
            self.aliases[key] = alias
            self.first_use[key] = node
        return self.aliases[key]

    def imports(self) -> list[ast.stmt]:
        stmts: list[ast.stmt] = []
        for (module, symbol), alias in self.aliases.items():
            where = self.first_use[(module, symbol)]
            name = ast.copy_location(ast.alias(name=symbol, asname=alias), where)
            stmts.append(ast.copy_location(ast.ImportFrom(module=module, names=[name], level=0), where))
            if (module, symbol) in self.subclassed:
                stmts.append(_subclass(alias, where))
        return stmts


def _subclass(alias: str, where: ast.expr) -> ast.stmt:
    '''`class <alias>_Subclass(<alias>): pass`, located at the first use'''
    base = ast.copy_location(ast.Name(id=alias, ctx=ast.Load()), where)
    body = ast.copy_location(ast.Pass(), where)
    return ast.copy_location(ast.ClassDef(
        name=alias + SUBCLASS_SUFFIX,
        bases=[base],
        keywords=[],
        body=[body],
        decorator_list=[],
        type_params=[]
    ), where)


def rewrite(
    tree: ast.Module,
    files: typing.Iterable[str],
    classes: typing.Optional[dict[str, list[str]]] = None,
    functions: typing.Optional[dict[str, list[str]]] = None
) -> ast.Module:
    source_tree = SourceTree(files, classes, functions)
    bound = _bindings(tree, source_tree)
    if not bound:
        return tree
    rewriter = _Rewriter(source_tree, bound)
    tree = rewriter.visit(tree)
    head = _head_length(tree.body)
    tree.body = tree.body[:head] + rewriter.imports() + tree.body[head:]
    return tree


def _head_length(body: list[ast.stmt]) -> int:
    '''the module docstring and `from __future__` imports stay first'''
    head = 0
    if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) and isinstance(body[0].value.value, str):
        head = 1
    while head < len(body) and isinstance(body[head], ast.ImportFrom) and body[head].module == '__future__':
        head += 1
    return head
