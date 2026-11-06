Rails.application.routes.draw do

  get  "/healthcheck", to: "translator#healthcheck"
  post "/to/native/cruby/ast", to: "translator#post_handler"
  post "/to/native/cruby/globals", to: "translator#globals_handler"

end
