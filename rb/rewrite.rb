# Bind global names to the file that defines them ( runner #146 ).
#
# Ruby has no imports : `DB.exec(sql)` in app/jobs/... reaches
#
#     ::DB = MiniSqlMultisiteConnection.instance
#
# in config/initializers/000-mini_sql.rb with nothing in between. So every
# file goes through the front twice :
#
# 1. `globals` : the global names a file defines, `::X = e` anywhere,
#    `X = e` at the top level ( not inside a class / module ), `$x = e`
#    anywhere, and its top level `class X` / `module X`. The worker keeps
#    the names one scanned file defines ( a class reopened elsewhere, like
#    `class ::Post` in a plugin, goes to the file zeitwerk would load it
#    from, `post.rb` ).
#
# 2. `rewrite` : a file that uses such a name gets, as its first statements,
#    one synthetic node per name,
#
#     { "type": "dhscanner_import", "name": "DB", "value": "config/initializers/000-mini_sql.rb", ... }
#
#    located at its first use, which the dhscanner parsers lower to a 1st
#    party `from <file> import DB`, like the python front does for a dotted
#    1st party access ( runner #131 ). A top level class is sent as
#
#     { "type": "dhscanner_class", "name": "MiniSqlMultisiteConnection", "value": "lib/...", ... }
#
#    located at its definition ( in `value` ), which the parsers lower to an
#    empty class of that name, so a call on it is a method of that class,
#    `kb_call_method_of_class( Call, instance, Class )`. A constant that no
#    scanned file defines, at any depth ( `MiniSql`, a gem ), is sent as
#
#     { "type": "dhscanner_gem", "name": "MiniSql", ... }
#
#    which the parsers lower to a 3rd party import, so the super
#    `MiniSql::ActiveRecordPostgres::Connection` keeps its qualified name.
#    The uses themselves are left as is.
#
# A name the file defines itself ( a constant of the same name, at any
# depth, or a class / module of that name ) is not bound : the local one wins.

require "set"

module DhscannerRewrite

  # these names belong to ruby, whoever assigns them ( `$stdout = StringIO.new` )
  PREDEFINED_GVARS = %w[
    $stdout $stderr $stdin $0 $PROGRAM_NAME $LOAD_PATH $LOADED_FEATURES
    $DEBUG $VERBOSE $FILENAME $SAFE $*
  ].freeze

  # a constant assigned in one of these is not a top level one
  SCOPES = %w[class module sclass].freeze

  IMPORT = "dhscanner_import".freeze
  CLASS = "dhscanner_class".freeze
  GEM = "dhscanner_gem".freeze

  # what the worker needs of a file ( `tree`, a native ast, as a hash ) :
  # the global names it `defines` ( see `defines` ), the `constants` it
  # defines at any depth, and the constant names it `uses`
  def self.globals(tree)
    {
      "defines" => defines(tree),
      "constants" => local_constants(tree).compact.sort,
      "uses" => used_names(tree)
    }
  end

  # the constant names `tree` uses, in first use order
  def self.used_names(tree)
    names = []
    walk(tree, 0) do |node, _|
      name = used_name(node)
      names << name if name && !name.start_with?("$") && !names.include?(name)
    end
    names
  end

  # the global names `tree` defines : a list of [ name, location ], the
  # location of a top level class name, nil otherwise
  def self.defines(tree)
    names = {}
    walk(tree, 0) do |node, depth|
      case node["type"]
      when "assign"
        name = global_assigned(node["target"], depth)
        names[name] = nil if name && !names.key?(name)
      when "class", "module"
        name = top_level_class(node, depth)
        next if name.nil?
        location = node["type"] == "class" ? node["constant"]["location"] : nil
        names[name] = location if !names.key?(name) || names[name].nil?
      end
    end
    names.to_a
  end

  # `class X` / `module X` / `class ::X` at the top level ( `class A::B` is not kept yet )
  def self.top_level_class(node, depth)
    constant = node["constant"]
    return nil unless depth == 0 && constant.is_a?(Hash)
    return nil unless %w[const_ref top_const_ref].include?(constant["type"])
    constant.dig("constant", "value")
  end

  # `globals` : { name => [ file, location ] }, the names a single scanned
  # file defines, `location` as in `defines` ; `external` : the constant
  # names no scanned file defines
  def self.rewrite(tree, globals, filename, external = [])
    globals ||= {}
    external = Set.new(external || [])
    return tree if globals.empty? && external.empty?
    local = local_constants(tree)
    uses = {}
    walk(tree, 0) do |node, _|
      name = used_name(node)
      next if name.nil? || uses.key?(name) || local.include?(name)
      file, definition = globals[name]
      if file.nil?
        uses[name] = [GEM, name, node["location"]] if external.include?(name)
        next
      end
      next if file == filename
      uses[name] = definition.nil? ? [IMPORT, file, node["location"]] : [CLASS, file, definition]
    end
    return tree if uses.empty?
    imports = uses.map do |name, (type, file, location)|
      { "type" => type, "location" => location, "name" => name, "value" => file, "comments" => [] }
    end
    statements = tree["statements"]
    statements["body"] = imports + statements["body"]
    tree
  end

  # `::X = e` anywhere, `X = e` at the top level, `$x = e` anywhere
  def self.global_assigned(target, depth)
    return nil unless target.is_a?(Hash)
    case target["type"]
    when "top_const_field"
      target.dig("constant", "value")
    when "var_field"
      value = target["value"]
      return nil unless value.is_a?(Hash)
      if value["type"] == "const" && depth == 0
        value["value"]
      elsif value["type"] == "gvar" && !PREDEFINED_GVARS.include?(value["value"])
        value["value"]
      end
    end
  end

  # `X` ( var_ref const ), `::X` ( top_const_ref ), `$x` ( var_ref gvar )
  def self.used_name(node)
    case node["type"]
    when "var_ref"
      value = node["value"]
      value["value"] if value.is_a?(Hash) && %w[const gvar].include?(value["type"])
    when "top_const_ref"
      node.dig("constant", "value")
    end
  end

  # constants this file defines at any depth, and its class / module names
  def self.local_constants(tree)
    names = Set.new
    walk(tree, 0) do |node, _|
      case node["type"]
      when "assign"
        target = node["target"]
        if target.is_a?(Hash) && target["type"] == "var_field" && target.dig("value", "type") == "const"
          names << target.dig("value", "value")
        end
      when "class", "module"
        names << node.dig("constant", "constant", "value")
      end
    end
    names
  end

  # depth first, in source order ; `depth` counts the enclosing class / module scopes
  def self.walk(node, depth, &block)
    case node
    when Hash
      yield node, depth
      inner = SCOPES.include?(node["type"]) ? depth + 1 : depth
      node.each_value { |v| walk(v, inner, &block) }
    when Array
      node.each { |v| walk(v, depth, &block) }
    end
  end

end
