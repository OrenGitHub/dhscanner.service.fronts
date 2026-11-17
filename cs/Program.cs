using Microsoft.AspNetCore.Mvc;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.Hosting;
using System.Text;
using System.Linq;
using System.IO;

var ParseOptions = new CSharpParseOptions(LanguageVersion.CSharp15);
var IdentifierProperties = new System.Collections.Concurrent.ConcurrentDictionary<Type, System.Reflection.PropertyInfo?>();

var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();

app.MapGet("/healthcheck", () => Results.Ok("ok"));

app.MapPost("/to/native/cs/ast", async (HttpRequest request, ILogger<Program> logger) =>
{
    if (request.HasFormContentType == false) {
        return Results.BadRequest("File not found.");
    }

    if (request.Form.Files["source"] == null) {
        return Results.BadRequest("File not found.");
    }

    var file = request.Form.Files["source"];
    var filename = file.FileName;
    
    // Read the file content
    using var reader = new StreamReader(file.OpenReadStream());
    string sourceCode = await reader.ReadToEndAsync();

    // Parse with the newest language version Roslyn knows : the default
    // options pin an older C# and turn newer syntax ( primary constructors,
    // collection expressions, `field` ) into IncompleteMember garbage
    var syntaxTree = CSharpSyntaxTree.ParseText(sourceCode, ParseOptions);

    if (syntaxTree is null) {
        return Results.BadRequest("File not found.");
    }

    // Roslyn recovers from syntax errors with a half tree ( missing /
    // skipped tokens ) : report the file as not natively parsed instead
    var errors = syntaxTree.GetDiagnostics()
        .Where(d => d.Severity == DiagnosticSeverity.Error)
        .ToList();
    if (errors.Any()) {
        logger.LogWarning("{filename}: {count} syntax errors, first: {first}", filename, errors.Count, errors[0]);
        return Results.UnprocessableEntity(string.Join("\n", errors.Take(10).Select(e => e.ToString())));
    }

    // return Results.Json(new { key = filename });

    // Get the root node of the syntax tree
    var root = syntaxTree.GetRoot();

    // Stringify the root node with locations
    var result = Stringify(root);

    // Return the AST as JSON
    return Results.Text(result);
});

app.Run();

string Stringify(SyntaxNode root)
{
    var result = new StringBuilder();
    Emit(root, result);
    return result.ToString();
}

// One node : {kind:K,value:V,location:{..},children: [..]} on a single line.
// Tokens ( keywords, punctuation, identifiers ) are not nodes, so `value`
// carries the text the grammar needs :
//   * a declaration's name ( class, method, parameter, variable, generic
//     name .. : every node with an `Identifier` token ),
//   * else the text of a leaf node ( literals, predefined types, `this` ),
//   * else null.
// It is always a quoted string ( or the bare word null ), sanitised like
// the java front so the lexer reads it as one \"[^\"]*\" token.
void Emit(SyntaxNode node, StringBuilder result)
{
    var lineSpan = node.GetLocation().GetLineSpan();
    var children = node.ChildNodes().ToList();
    var identifier = IdentifierOf(node);
    string? text = identifier ?? (children.Count == 0 ? node.ToString() : null);

    result.Append("{kind:").Append(node.Kind()).Append(',');
    result.Append("value:").Append(text is null ? "null" : Quote(text)).Append(',');
    result.Append("location:{");
    result.Append("startLine:").Append(lineSpan.StartLinePosition.Line + 1).Append(',');
    result.Append("startColumn:").Append(lineSpan.StartLinePosition.Character + 1).Append(',');
    result.Append("endLine:").Append(lineSpan.EndLinePosition.Line + 1).Append(',');
    result.Append("endColumn:").Append(lineSpan.EndLinePosition.Character + 1);
    result.Append("},children: [");
    for (var i = 0; i < children.Count; i++) {
        if (i > 0) result.Append(',');
        Emit(children[i], result);
    }
    result.Append("]}");
}

string? IdentifierOf(SyntaxNode node)
{
    var property = IdentifierProperties.GetOrAdd(node.GetType(), t => t.GetProperty("Identifier"));
    if (property?.GetValue(node) is SyntaxToken token && token.IsMissing == false) {
        return token.ValueText;
    }
    return null;
}

// drop quotes / backslashes / control bytes, whitespace becomes a space
string Quote(string s)
{
    var b = new StringBuilder(s.Length + 2);
    b.Append('"');
    foreach (var c in s) {
        if (c == '"' || c == '\\') continue;
        if (char.IsWhiteSpace(c)) { b.Append(' '); continue; }
        if (char.IsControl(c)) continue;
        b.Append(c);
    }
    return b.Append('"').ToString();
}
