require "json"
require "syntax_tree"

class TranslatorController < ApplicationController

  def healthcheck

    render plain: JSON.dump({ "healthy": true })

  end

  def post_handler

    program = native_program

    # `ruby_globals` : json { name : [ file, location ] }, the global names a
    # single scanned file defines ; `ruby_external` : json [ name ], the
    # constants no scanned file defines ( runner #146 )
    globals = params["ruby_globals"]
    external = params["ruby_external"]
    if globals.present? || external.present?
      filename = params["source"].original_filename
      program = DhscannerRewrite.rewrite(
        JSON.parse(JSON.dump(program)),
        globals.present? ? JSON.parse(globals) : {},
        filename,
        external.present? ? JSON.parse(external) : []
      )
    end

    # return AST (json format)
    render plain: JSON.dump(program)

  end

  # the global names the file defines, its constants and the constants it uses ( json )
  def globals_handler

    render plain: JSON.dump(DhscannerRewrite.globals(JSON.parse(JSON.dump(native_program))))

  end

  private

  # the native ast of the sent file
  def native_program

    # get the sent file entity
    source = params["source"]

    # read ruby source code
    file = File.open(source.tempfile)
    content = file.read
    file.close

    # parse it
    SyntaxTree.parse(content)

  end

end
