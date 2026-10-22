;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.worker-notifications-test
  (:require
   [app.config :as cf]
   [app.db :as db]
   [app.worker :as wrk]
   [clojure.test :as t])
  (:import
   java.sql.Connection
   java.time.Duration
   org.postgresql.ds.PGSimpleDataSource
   org.postgresql.PGConnection))

(defn- connection
  ^Connection []
  (let [config (cf/read-config :prefix "penpot-test"
                               :default {:database-uri "postgresql://postgres/penpot_test"})
        source (doto (PGSimpleDataSource.)
                 (.setURL (str "jdbc:" (:database-uri config))))]
    (when-let [username (:database-username config)]
      (.setUser source username))
    (when-let [password (:database-password config)]
      (.setPassword source password))
    (.getConnection source)))

(defn- prepare-publisher!
  [conn]
  ;; A session-local table exercises the real submit SQL without modifying
  ;; the application's tasks or requiring its full Integrant system.
  (db/exec-one! conn ["CREATE TEMPORARY TABLE task (
    id uuid PRIMARY KEY, name text, props jsonb, queue text, label text,
    priority integer, max_retries integer, created_at timestamptz,
    modified_at timestamptz, scheduled_at timestamptz)"]))

(t/deftest notify-only-after-task-commit
  (with-open [listener (connection)
              publisher (connection)]
    (prepare-publisher! publisher)
    (db/exec-one! listener ["LISTEN penpot_worker"])
    (.setAutoCommit publisher false)
    (let [^PGConnection pg (db/unwrap listener PGConnection)
          id               (wrk/submit! {::db/conn publisher
                                         ::wrk/task :process-webhook-event
                                         ::wrk/params {:name "update-file"}})]
      (t/is (empty? (.getNotifications pg 50)) "Uncommitted tasks do not wake a dispatcher")
      (.commit publisher)
      (t/is (= 1 (count (.getNotifications pg 1000))) "A committed task wakes the listener")
      (t/is (= id (:id (db/exec-one! publisher ["SELECT id FROM task"])))))))

(t/deftest rollback-does-not-publish-a-task
  (with-open [listener (connection)
              publisher (connection)]
    (prepare-publisher! publisher)
    (db/exec-one! listener ["LISTEN penpot_worker"])
    (.setAutoCommit publisher false)
    (wrk/submit! {::db/conn publisher ::wrk/task :run-webhook ::wrk/params {}})
    (.rollback publisher)
    (let [^PGConnection pg (db/unwrap listener PGConnection)]
      (t/is (empty? (.getNotifications pg 50)))
      (t/is (zero? (:total (db/exec-one! publisher ["SELECT count(*) AS total FROM task"])))))))

(t/deftest update-file-has-no-default-batch-delay
  (t/is (.isZero ^Duration (:webhook-update-file-batch-timeout cf/default))))
