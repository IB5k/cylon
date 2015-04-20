(ns cylon.oauth.server.logout
  (:require
   [bidi.bidi :refer (RouteProvider tag)]
   [com.stuartsierra.component :refer (using)]
   [cylon.token-store :refer (purge-token!)]
   [cylon.session :refer (session respond-close-session!)]
   [ring.util.response :refer (redirect)]
   [ring.middleware.params :refer (params-request)]
   [schema.core :as s]
   [plumbing.core :refer (<-)]))

(defrecord Logout [session-store uri-context]
  RouteProvider
  (routes [component]
    [uri-context
     {"/logout"
      (->
       (fn [req]
         ;; Logout
         ;; TODO :-
         ;; "At the logout endpoint, the OP SHOULD ask the
         ;; End-User whether he wants to log out of the OP as
         ;; well. If the End-User says "yes", then the OP MUST
         ;; log out the End-User." --
         ;; http://openid.net/specs/openid-connect-session-1_0.html

         ;; When there is an access-token associated with this session, we
         ;; shall purge it.
         (when-let [access-token (:cylon/access-token (session session-store req))]
           (purge-token! session-store access-token))

         (let [post-logout-redirect-uri
               (-> req params-request :query-params (get "post_logout_redirect_uri"))]
           (respond-close-session!
            session-store req
            (if post-logout-redirect-uri
              (redirect post-logout-redirect-uri)
              {:status 200 :body "Logged out of auth server"}))))
       (tag ::logout))}]))

(def new-logout-schema
  {:uri-context s/Str})

(defn new-logout [& {:as opts}]
  (->> opts
       (merge {:uri-context ""})
       (s/validate new-logout-schema)
       map->Logout
       (<- (using [:session-store]))))
