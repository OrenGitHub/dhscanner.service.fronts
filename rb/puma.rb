# the parse ( SyntaxTree / Ripper ) is cpu bound and holds the GVL, so the
# threads of one puma process share a single core : one process per core
# ( runner #150 ). The default rails puma.rb has threads only.
require "etc"

workers ENV.fetch("WEB_CONCURRENCY") { Etc.nprocessors }
threads_count = ENV.fetch("RAILS_MAX_THREADS", 3)
threads threads_count, threads_count

# load the app once, the workers fork from it and share its memory
preload_app!

port ENV.fetch("PORT", 3000)
pidfile ENV["PIDFILE"] if ENV["PIDFILE"]
