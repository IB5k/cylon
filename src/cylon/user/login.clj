;; Copyright © 2014, JUXT LTD. All Rights Reserved.

(ns cylon.user.login
  (:require
   [clojure.tools.logging :refer :all]
   [clojure.string :as string]
   [cylon.user.protocols :as p]
   [cylon.authentication.protocols :refer (RequestAuthenticator AuthenticationHandshake)]
   [cylon.password :refer (verify-password)]
   [cylon.password.protocols :refer (PasswordVerifier)]
   [cylon.session :refer (session assoc-session-data! respond-with-new-session! respond-close-session!)]
   [cylon.session.protocols :refer (SessionStore)]
   [cylon.user :refer (get-user get-user-by-email FormField render-login-form)]
   [cylon.util :refer (as-query-string uri-with-qs Request wrap-schema-validation)]
   [bidi.bidi :refer (RouteProvider tag)]
   [modular.bidi :refer (path-for)]
   [ring.util.response :refer (redirect redirect-after-post)]
   [ring.middleware.params :refer (params-request)]
   [plumbing.core :refer (<-)]
   [com.stuartsierra.component :refer (Lifecycle using)]
   [schema.core :as s]
   [modular.component.co-dependency :refer (co-using)])
  (:import (java.net URLEncoder)))

(defn email? [s]
  (re-matches #".+@.+" s))

(defrecord Login [user-store session-store renderer password-verifier fields uri-context *router]
  Lifecycle
  (start [component]
    (s/validate
     {:user-store (s/protocol p/UserStore)
      :session-store (s/protocol SessionStore)
      :renderer (s/protocol p/LoginFormRenderer)
      :password-verifier (s/protocol PasswordVerifier)
      :fields [FormField]
      :uri-context s/Str
      :*router s/Any ;; you can't get specific protocol of a codependency in start time
      }
     component))
  (stop [component] component)

  AuthenticationHandshake
  (initiate-authentication-handshake [component req]
    (assert (:routes @*router))
    (if-let [p (path-for @*router ::login-form)]
      (let [loc (str p (as-query-string {"post_login_redirect" (URLEncoder/encode (uri-with-qs req))}))]
        (debugf "Redirecting to %s" loc)
        (redirect loc))
      (throw (ex-info "No path to login form" {}))))

  RequestAuthenticator
  (authenticate [component req]
    (session session-store req))

  RouteProvider
  (routes [component]
    [uri-context
     {"/login"
      {:get
       (->
        (fn [req]
          (let [qparams (-> req params-request :query-params)
                post-login-redirect (get qparams "post_login_redirect")]
            #_(assert post-login-redirect "Request query-string must contain a post_login_redirect parameter")
            {:status 200
             :body (render-login-form
                    renderer req
                    {:form {:method :post
                            :action (path-for @*router ::process-login-attempt)
                            :fields (if post-login-redirect
                                      (conj fields {:name "post_login_redirect" :value post-login-redirect :type "hidden"})
                                      fields)}
                     :login-failed? (Boolean/valueOf (get qparams "login_failed"))})}))
        wrap-schema-validation
        (tag ::login-form)
        )

       :post
       (->
        (fn [req]
          (let [params (-> req params-request :form-params)
                ;; TODO Standardize "user", perhaps use a cylon prefix
                ;; We must have this field passed, plus password, otherwise it's no good
                userid (some-> (get params "user") string/trim)
                password (get params "password")
                session (session session-store req)
                post-login-redirect (get params "post_login_redirect")
                _ (debugf "Form params posted to login form are %s" params)
                user (when userid
                       ((if (email? userid) get-user-by-email get-user) user-store userid))
                _ (debugf "User attempting login looked up: %s" user)]

            ;; By default, we can login with a username or email address (they might well be the same thing).

            (if (and user (verify-password password-verifier (:uid user) password))
              ;; Login successful!
              (do
                (debugf "Login successful!")
                (respond-with-new-session!
                 session-store req
                 {:cylon/subject-identifier (:uid user)
                  :cylon/user user}
                 (if post-login-redirect
                   (redirect-after-post post-login-redirect)
                   {:status 200 :body "Login successful"})))

              ;; Login failed!
              (do
                (debugf "Login failed!")

                ;; TODO I think the best thing to do here is to create a
                ;; session anyway - we have been posted after all. We can
                ;; store in the session things like number of failed
                ;; attempts (to attempt to prevent brute-force hacking
                ;; attempts by limiting the number of sessions that can be
                ;; used by each remote IP address). If we do this, then the
                ;; post_login_redirect must be ascertained from the
                ;; query-params, and then from the session.

                (redirect-after-post
                 (str (path-for @*router ::login-form)
                      ;; We must be careful to add back the query string
                      (as-query-string
                       (merge
                        (when post-login-redirect
                          {"post_login_redirect" (URLEncoder/encode post-login-redirect)})
                        ;; Add a login_failed to help with indicating the failure to the user.
                        {"login_failed" true}
                        ))))))))
        wrap-schema-validation
        (tag ::process-login-attempt)
        )}

      "/logout"
      {:get
       (->
        (fn [req]
          (let [qparams (-> req params-request :query-params)
                post-logout-redirect (get qparams "post_logout_redirect")]
            (respond-close-session! session-store req (redirect post-logout-redirect))))
        wrap-schema-validation
        (tag ::logout)
        )}
      }]))

(defn new-login [& {:as opts}]
  (->> opts
       (merge {:fields [{:name "user" :label "User" :type "text" :placeholder "id or email"}
                        {:name "password" :label "Password" :type "password" :placeholder "password"}]
               :uri-context ""})
       (s/validate {:fields [FormField]
                    :uri-context s/Str})
       map->Login
       (<- (using [:password-verifier :session-store :renderer :user-store]))
       (<- (co-using [:router]))
       ))
