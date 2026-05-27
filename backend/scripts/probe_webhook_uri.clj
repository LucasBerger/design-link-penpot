(ns probe-webhook-uri
  "Probe a webhook URL the same way Penpot does (HEAD on create, POST on delivery).

  Run inside penpot-devenv:

    cd /home/penpot/penpot/backend
    source scripts/_env.local
    clojure -M:probe-webhook
    clojure -M:probe-webhook http://verity-webhook:3001/api/webhook/penpot
    clojure -M:probe-webhook --penpot http://host.docker.internal:3001/api/webhook/penpot

  Default mode uses java-http-clj (same library as app.http.client).
  --penpot adds SSRF validation via app.util.ssrf (same as create-webhook)."
  (:require
   [app.common.time :as ct]
   [app.config :as cf]
   [app.http.client :as penpot-http]
   [app.util.ssrf :as ssrf]
   [clojure.data.json :as json]
   [cuerdas.core :as str]
   [java-http-clj.core :as http]))

(def default-uri
  "http://host.docker.internal:3003/api/webhook/penpot")

(def sample-event
  {:name "update-file"
   ;; Use a file id that is not in Verity DB so POST only tests HTTP transport (no getFile).
   :props {:id "00000000-0000-0000-0000-000000000000"
           :name "probe – Design Link"
           :teamId "89a7abe3-a439-80c9-8008-03e4b3ef9df3"
           :projectId "89a7abe3-a439-80c9-8008-05e0c1d9af40"}
   :profileId "89a7abe3-a439-80c9-8008-03e4b3ee9ffe"
   :createdAt "2026-05-26T23:08:27.679Z"
   :trackedAt "2026-05-26T23:08:25.003Z"})

(defn- build-client
  ([] (http/build-client {:connect-timeout 30000 :follow-redirects :never}))
  ([version-kw]
   (http/build-client {:connect-timeout 30000
                       :follow-redirects :never
                       :version version-kw})))

(defn- println-status [label result]
  (println (format "\n=== %s ===" label))
  (if-let [error (:error result)]
    (do
      (println "ERROR" (class error))
      (println "  message:" (.getMessage error))
      (when-let [data (ex-data error)]
        (println "  ex-data:" (pr-str data))))
    (do
      (println "status:" (:status result))
      (when-let [headers (:headers result)]
        (println "headers:" (pr-str (select-keys headers ["content-length" "connection" "date"]))))
      (let [body (:body result)]
        (when (and body (not (str/blank? (str body))))
          (println "body:" (if (> (count (str body)) 400)
                             (str (subs (str body) 0 400) "…")
                             body)))))))

(defn- http-clj-head [client uri-str]
  (try
    (http/send {:method :head
                :uri uri-str
                :timeout (ct/duration "3s")}
               {:client client :as :string})
    (catch Throwable t
      {:error t})))

(defn- http-clj-post [client uri-str body]
  (try
    (http/send {:method :post
                :uri uri-str
                :headers {"content-type" "application/json"
                          "user-agent" "penpot-probe/1.0"}
                :timeout (ct/duration "30s")
                :body body}
               {:client client :as :string})
    (catch Throwable t
      {:error t})))

(defn- penpot-head [client uri-str]
  (try
    (penpot-http/req client
                     {:method :head
                      :uri uri-str
                      :timeout (ct/duration "3s")})
    (catch Throwable t
      {:error t})))

(defn- penpot-post [client uri-str body]
  (try
    (penpot-http/req client
                     {:method :post
                      :uri uri-str
                      :headers {"content-type" "application/json"
                                "user-agent" "penpot-probe/1.0"}
                      :timeout (ct/duration "30s")
                      :body body}
                     {:response-type :input-stream :sync? true})
    (catch Throwable t
      {:error t})))

(defn- parse-args [args]
  (let [penpot? (boolean (some #(= % "--penpot") args))
        uri (or (first (remove #{"--penpot"} args)) default-uri)]
    {:uri uri :penpot? penpot?}))

(defn- ok? [result]
  (and (nil? (:error result))
       (#{200 204} (:status result))))

(defn run-probe
  [{:keys [uri penpot?]}]
  (println "Webhook URI probe")
  (println "  uri:" uri)
  (println "  mode:" (if penpot? "penpot (SSRF + app.http.client/req)" "java-http-clj only"))
  (when penpot?
    (println "  ssrf-allowed-hosts:" (pr-str (cf/get :ssrf-allowed-hosts #{}))))

  (let [client (build-client)
        client-http11 (build-client :http1.1)
        body (json/write-str sample-event {:key-fn name})
        results
        (if penpot?
          (do
            (try
              (ssrf/validate-uri uri)
              (println "\nSSRF validate-uri: OK")
              (catch Throwable t
                (println "\nSSRF validate-uri FAILED:")
                (println " " (.getMessage t))
                (println "  ex-data:" (pr-str (ex-data t)))
                (System/exit 1)))
            [{:label "Penpot HEAD (create-webhook validation)"
              :result (penpot-head client uri)}
             {:label "Penpot POST (run-webhook delivery)"
              :result (penpot-post client uri body)}])
          [{:label "java-http-clj HEAD (default client)"
            :result (http-clj-head client uri)}
           {:label "java-http-clj HEAD (HTTP/1.1 forced)"
            :result (http-clj-head client-http11 uri)}
           {:label "java-http-clj POST (default client)"
            :result (http-clj-post client uri body)}
           {:label "java-http-clj POST (HTTP/1.1 forced)"
            :result (http-clj-post client-http11 uri body)}])]

    (doseq [{:keys [label result]} results]
      (println-status label result))

    (if (every? (comp ok? :result) results)
      (println "\nAll probes returned 200/204.")
      (do
        (println "\nOne or more probes failed (compare with Penpot webhook errors).")
        (System/exit 1)))))

(defn -main [& args]
  (run-probe (parse-args args)))
