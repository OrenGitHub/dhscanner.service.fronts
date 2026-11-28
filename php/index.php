<?php
/**
 * Tiny HTTP front for dhscanner's PHP pipeline.
 *
 * Replaces the previous Laravel-based front: that one shipped the entire
 * `laravel/laravel` skeleton (HTTP kernel, session middleware, CSRF
 * verifier, routing layer, view layer, ...) just to expose a couple of
 * URLs. CSRF middleware in particular silently 419'd every POST the
 * workers sent, which was a recurring foot-gun.
 *
 * This file IS the entire HTTP surface, served by `php -S` (PHP's
 * built-in dev / single-tenant server -- fine for the in-cluster usage
 * pattern we have here):
 *
 *   GET  /healthcheck    -> 200 {"healthy": true}
 *   POST /to/php/ast     -> NodeDumper output of nikic/PhpParser ; a
 *                           `*.blade.php` upload is compiled to plain PHP
 *                           first ( Laravel's BladeCompiler )
 *
 * `php -S` invokes this script for every incoming request. We dispatch
 * on (REQUEST_METHOD, URI path) and emit the body directly via `echo`.
 * Returning `true` (or simply not returning) tells the built-in server
 * that the request was handled; returning `false` would tell it to fall
 * back to static-file serving from the document root, which we never want.
 *
 * Blade : a `*.blade.php` file ( the upload's name ) is compiled by
 * Laravel's own BladeCompiler, and the whole compiled PHP is parsed like
 * any php file : `@php .. @endphp` and `{{ $x }}` become `<?php .. ?>`, the
 * markup stays inline html ( nulled as usual ), so the lines stay where
 * they were. `@include( 'a.b' )` stays a call, `$__env->make( 'a.b', .. )`.
 * The previous front kept only the `<?php ?>` chunks, joined, and dropped
 * the includes.
 */

require __DIR__ . '/vendor/autoload.php';

use PhpParser\Error;
use PhpParser\NodeDumper;
use PhpParser\ParserFactory;
use PhpParser\Node;
use PhpParser\NodeTraverser;
use PhpParser\NodeVisitorAbstract;
use Illuminate\Filesystem\Filesystem;
use Illuminate\View\Compilers\BladeCompiler;

/**
 * Same string / inline-HTML normalization the previous Laravel front
 * applied. The downstream Haskell parser (dhscanner.1.parsers/PhpParser.y)
 * expects this exact shape, so the body is byte-equivalent to the
 * previous implementation; only the HTTP layer around it changed.
 */
final class StringNormalizer extends NodeVisitorAbstract
{
    public function enterNode(Node $node)
    {
        if (
            ($node instanceof Node\Scalar\String_) ||
            ($node instanceof Node\Scalar\EncapsedStringPart)
        ) {
            $raw = str_replace('"', '', $node->value);
            $one_liner = preg_replace('/\s+/', ' ', $raw);
            $node->value = '"' . $one_liner . '"';
        }
        if ($node instanceof Node\Stmt\InlineHTML) {
            $node->value = 'null';
        }
        return null;
    }
}

/**
 * A variable a blade template reads but never binds : Laravel injects it
 * ( the data array of `view( 'a.b', [ 'k' => .. ] )`, or every variable of
 * the parent template for `@include` ). Dumped as
 *
 *     BladePhp_UnboundVar[l:c - l:c]( name: Scalar_String[..]( value: "k" ) )
 *
 * and lowered by the parsers to `<dhscanner-instrumentation>[unbound-var-blade-php-file]( 'k' )`.
 */
final class BladePhpUnboundVar extends Node\Expr
{
    public Node\Scalar\String_ $name;

    public function __construct(Node\Scalar\String_ $name, array $attributes = [])
    {
        $this->attributes = $attributes;
        $this->name = $name;
    }

    public function getSubNodeNames(): array
    {
        return ['name'];
    }

    public function getType(): string
    {
        return 'BladePhp_UnboundVar';
    }
}

/**
 * The unbound variables of a compiled blade file : every top level read of
 * a `$name` no earlier statement bound, in order of execution ( the right
 * side of an assignment before its left side ). Functions, methods,
 * closures and classes have scopes of their own, so they are not entered
 * ( closure `use ( $x )` is a read ). A binding in a branch counts as a
 * binding after it. Superglobals, `$this` and Blade's own internals
 * ( `$__env`, `$__data`, .. ) are not reported.
 *
 * Returns name => the first unbound read ( its node, for the location ).
 */
final class UnboundVars
{
    private const IGNORED = [
        'this', 'GLOBALS', '_SERVER', '_GET', '_POST', '_FILES', '_COOKIE',
        '_SESSION', '_REQUEST', '_ENV', 'argv', 'argc',
    ];

    /** @var array<string, true> */
    private array $bound = [];

    /** @var array<string, Node\Expr\Variable> */
    private array $unbound = [];

    /** @param Node[] $stmts */
    public static function of(array $stmts): array
    {
        $walker = new self();
        $walker->walk($stmts);
        return $walker->unbound;
    }

    private function walk($node): void
    {
        if (is_array($node)) {
            foreach ($node as $n) {
                $this->walk($n);
            }
            return;
        }
        if (!($node instanceof Node)) {
            return;
        }
        if (
            $node instanceof Node\Stmt\Function_ ||
            $node instanceof Node\Stmt\ClassLike ||
            $node instanceof Node\Expr\ArrowFunction
        ) {
            return;
        }
        if ($node instanceof Node\Expr\Closure) {
            foreach ($node->uses as $use) {
                $this->read($use->var);
            }
            return;
        }
        if ($node instanceof Node\Expr\Assign || $node instanceof Node\Expr\AssignRef) {
            $this->walk($node->expr);
            $this->bind($node->var);
            return;
        }
        if ($node instanceof Node\Stmt\Foreach_) {
            $this->walk($node->expr);
            $this->bind($node->keyVar);
            $this->bind($node->valueVar);
            $this->walk($node->stmts);
            return;
        }
        if ($node instanceof Node\Stmt\Catch_) {
            $this->bind($node->var);
            $this->walk($node->stmts);
            return;
        }
        if ($node instanceof Node\Stmt\Global_ || $node instanceof Node\Stmt\Static_) {
            foreach ($node->vars as $v) {
                $this->bind($v instanceof Node\Stmt\StaticVar ? $v->var : $v);
            }
            return;
        }
        if ($node instanceof Node\Expr\Variable) {
            $this->read($node);
            return;
        }
        foreach ($node->getSubNodeNames() as $sub) {
            $this->walk($node->$sub);
        }
    }

    /** an assignment target : `$x`, `$x[ .. ]`, `[ $a, $b ]` / `list( .. )` ; anything else is read */
    private function bind($target): void
    {
        if ($target instanceof Node\Expr\Variable && is_string($target->name)) {
            $this->bound[$target->name] = true;
            return;
        }
        if ($target instanceof Node\Expr\ArrayDimFetch) {
            $this->walk($target->dim);
            $this->bind($target->var);
            return;
        }
        if ($target instanceof Node\Expr\Array_ || $target instanceof Node\Expr\List_) {
            foreach ($target->items as $item) {
                if ($item !== null) {
                    $this->walk($item->key);
                    $this->bind($item->value);
                }
            }
            return;
        }
        $this->walk($target);
    }

    private function read($var): void
    {
        if (!($var instanceof Node\Expr\Variable)) {
            $this->walk($var);
            return;
        }
        $name = $var->name;
        if (!is_string($name)) {
            $this->walk($name);
            return;
        }
        if (
            isset($this->bound[$name]) ||
            isset($this->unbound[$name]) ||
            in_array($name, self::IGNORED, true) ||
            str_starts_with($name, '__')
        ) {
            return;
        }
        $this->unbound[$name] = $var;
    }
}

/**
 * Prepend `$k = BladePhp_UnboundVar( 'k' );` for every unbound variable,
 * located at its first unbound read, so the dataflow from the injected
 * value starts there.
 *
 * @param Node[] $stmts
 * @return Node[]
 */
function bind_unbound_vars(array $stmts): array
{
    $prologue = [];
    foreach (UnboundVars::of($stmts) as $name => $read) {
        $attrs = $read->getAttributes();
        $value = new BladePhpUnboundVar(new Node\Scalar\String_($name, $attrs), $attrs);
        $assign = new Node\Expr\Assign(new Node\Expr\Variable($name, $attrs), $value, $attrs);
        $prologue[] = new Node\Stmt\Expression($assign, $attrs);
    }
    return array_merge($prologue, $stmts);
}

/**
 * A php superglobal entry with a constant key ( `$GLOBALS['k']` ; `$_GET` /
 * `$_POST` later ). Dumped as
 *
 *     NativePhp_Globals[l:c - l:c]( name: Scalar_String( "GLOBALS" ) key: Scalar_String( "k" ) )            read
 *     NativePhp_Globals[l:c - l:c]( name: .. key: .. value: <exp> )                                          write
 *
 * and lowered by the parsers to `<dhscanner-instrumentation>[native-php-globals-read]( 'GLOBALS', 'k' )` /
 * `<dhscanner-instrumentation>[native-php-globals-write]( 'GLOBALS', 'k', v )`.
 */
final class NativePhpGlobals extends Node\Expr
{
    public Node\Scalar\String_ $name;
    public Node\Scalar\String_ $key;
    public ?Node\Expr $value;

    public function __construct(Node\Scalar\String_ $name, Node\Scalar\String_ $key, ?Node\Expr $value, array $attributes = [])
    {
        $this->attributes = $attributes;
        $this->name = $name;
        $this->key = $key;
        $this->value = $value;
    }

    public function getSubNodeNames(): array
    {
        return $this->value === null ? ['name', 'key'] : ['name', 'key', 'value'];
    }

    public function getType(): string
    {
        return 'NativePhp_Globals';
    }
}

/**
 * `$GLOBALS['k']` -> NativePhp_Globals( 'GLOBALS', 'k' ) ; `$GLOBALS['k'] = v`
 * -> NativePhp_Globals( 'GLOBALS', 'k', v ). Any php file. A dynamic key
 * ( `$GLOBALS[ $k ]` ) stays as is. Bottom up : the read is replaced first,
 * then the assignment around it becomes the write.
 */
final class NativeGlobalsNormalizer extends NodeVisitorAbstract
{
    private const SUPERGLOBALS = ['GLOBALS'];

    public function leaveNode(Node $node)
    {
        if (
            $node instanceof Node\Expr\ArrayDimFetch &&
            $node->var instanceof Node\Expr\Variable &&
            is_string($node->var->name) &&
            in_array($node->var->name, self::SUPERGLOBALS, true) &&
            $node->dim instanceof Node\Scalar\String_
        ) {
            $name = new Node\Scalar\String_($node->var->name, $node->var->getAttributes());
            return new NativePhpGlobals($name, $node->dim, null, $node->getAttributes());
        }
        if (
            $node instanceof Node\Expr\Assign &&
            $node->var instanceof NativePhpGlobals &&
            $node->var->value === null
        ) {
            return new NativePhpGlobals($node->var->name, $node->var->key, $node->expr, $node->getAttributes());
        }
        return null;
    }
}

/**
 * Laravel view names -> the template file they render, by Laravel's default
 * convention ( dots, or slashes, are directories under `resources/views`,
 * a leading slash dropped, `.blade.php` appended ) :
 *
 *     view( 'a.b', [ .. ] )                ->  view( 'resources/views/a/b.blade.php', [ .. ] )
 *     @include( 'a.b' )  ( compiled )      ->  $__env->make( 'resources/views/a/b.blade.php', .. )
 *
 * `view` is the global helper ( any php file, blade or not ), `$__env->make`
 * is what Blade compiles `@include` ( and `@extends` ) to. Only a constant
 * name is rewritten ; a namespaced `pkg::a.b` or a dynamic name stays as is.
 */
final class ViewNameNormalizer extends NodeVisitorAbstract
{
    public function enterNode(Node $node)
    {
        $isView =
            $node instanceof Node\Expr\FuncCall &&
            $node->name instanceof Node\Name &&
            strtolower($node->name->getLast()) === 'view' &&
            count($node->name->getParts()) === 1;
        $isMake =
            $node instanceof Node\Expr\MethodCall &&
            $node->var instanceof Node\Expr\Variable &&
            $node->var->name === '__env' &&
            $node->name instanceof Node\Identifier &&
            $node->name->toString() === 'make';
        if (!$isView && !$isMake) {
            return null;
        }
        $first = $node->args[0] ?? null;
        if (!($first instanceof Node\Arg) || !($first->value instanceof Node\Scalar\String_)) {
            return null;
        }
        $name = $first->value->value;
        if (preg_match('#^/?[A-Za-z0-9_\-]+([./][A-Za-z0-9_\-]+)*$#', $name) !== 1) {
            return null;
        }
        $path = str_replace('.', '/', ltrim($name, '/'));
        $first->value->value = 'resources/views/' . $path . '.blade.php';
        return null;
    }
}

/**
 * Read the uploaded `source` file from a multipart POST.
 *
 * Returns the file's raw bytes on success, or null if either no file was
 * uploaded under the `source` field or the upload failed (PHP signals the
 * failure mode through `$_FILES['source']['error']`).
 */
function read_uploaded_source(): ?string
{
    if (!isset($_FILES['source'])) {
        return null;
    }
    $info = $_FILES['source'];
    if (!is_array($info) || ($info['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
        return null;
    }
    $tmp = $info['tmp_name'] ?? '';
    if ($tmp === '' || !is_readable($tmp)) {
        return null;
    }
    $bytes = @file_get_contents($tmp);
    return $bytes === false ? null : $bytes;
}

function uploaded_source_is_blade(): bool
{
    $name = $_FILES['source']['name'] ?? '';
    return is_string($name) && str_ends_with($name, '.blade.php');
}

/**
 * Blade -> plain PHP, or null when the compiler throws. compileString
 * writes nothing, the cache path is only required by the constructor.
 */
function compile_blade(string $template): ?string
{
    $compiler = new BladeCompiler(new Filesystem(), sys_get_temp_dir());
    try {
        return $compiler->compileString($template);
    } catch (\Throwable $e) {
        return null;
    }
}

function handle_healthcheck(): void
{
    header('Content-Type: application/json');
    echo json_encode(['healthy' => true]);
}

function handle_to_php_ast(): void
{
    $code = read_uploaded_source();
    if ($code === null) {
        // Match the previous Laravel front's sentinel exactly: the
        // workers + dhscanner.1.parsers loop both already special-case
        // a literal "ERROR" body as "front-side failure, drop this file".
        echo "ERROR";
        return;
    }
    $blade = uploaded_source_is_blade();
    if ($blade) {
        $code = compile_blade($code);
        if ($code === null) {
            echo "ERROR";
            return;
        }
    }

    $parser = (new ParserFactory())->createForNewestSupportedVersion();
    try {
        $ast = $parser->parse($code);
    } catch (Error $e) {
        echo "ERROR";
        return;
    }
    if ($ast === null) {
        echo "ERROR";
        return;
    }

    if ($blade) {
        $ast = bind_unbound_vars($ast);
    }

    // its own pass : the strings it creates must reach StringNormalizer below
    $globals = new NodeTraverser();
    $globals->addVisitor(new NativeGlobalsNormalizer());
    $ast = $globals->traverse($ast);

    $traverser = new NodeTraverser();
    $traverser->addVisitor(new ViewNameNormalizer());
    $traverser->addVisitor(new StringNormalizer());
    $ast = $traverser->traverse($ast);

    $dumper = new NodeDumper(['dumpPositions' => true]);
    echo $dumper->dump($ast, $code) . "\n";
}

function dispatch(): void
{
    $method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
    $uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?? '/';

    if ($method === 'GET' && $uri === '/healthcheck') {
        handle_healthcheck();
        return;
    }
    if ($method === 'POST' && $uri === '/to/php/ast') {
        handle_to_php_ast();
        return;
    }

    http_response_code(404);
    header('Content-Type: text/plain');
    echo "not found: {$method} {$uri}\n";
}

dispatch();
return true;
