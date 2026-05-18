(ns frontend-tests.workspace-design-link-test
  (:require
   [app.main.ui.workspace.design-link :as sut]
   [cljs.test :as t :include-macros true]))

(t/deftest design-link-meta-from-fdata
  (t/is (nil? (sut/design-link-meta-from-fdata nil)))
  (t/is (nil? (sut/design-link-meta-from-fdata {})))
  (let [fdata {:plugin-data {:design-link {"project-id" "p1"
                                            "dashboard-base-url" "https://dash.example"
                                            "api-base-url" "https://api.example"}}}]
    (t/is (= {:project-id "p1"
              :dashboard-base-url "https://dash.example"
              :api-base-url "https://api.example"}
             (sut/design-link-meta-from-fdata fdata))))
  (let [fdata {:plugin-data {:design-link {:project-id "p2"}}}]
    (t/is (= {:project-id "p2"
              :dashboard-base-url "http://localhost:3000"
              :api-base-url "http://localhost:3001"}
             (sut/design-link-meta-from-fdata fdata)))))
