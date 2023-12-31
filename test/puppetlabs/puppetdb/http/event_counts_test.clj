(ns puppetlabs.puppetdb.http.event-counts-test
  (:require [clojure.java.io :refer [resource]]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [puppetlabs.puppetdb.testutils.http
             :refer [are-error-response-headers
                     deftest-http-app
                     query-response query-result
                     vector-param
                     ordered-query-result]]
            [puppetlabs.puppetdb.testutils.reports :refer [with-corrective-change
                                                           without-corrective-change
                                                           store-example-report!]]
            [puppetlabs.puppetdb.time :refer [now]])
  (:import
   (java.net HttpURLConnection)))

(def endpoints [[:v4 "/v4/event-counts"]])

(def example-catalog
  (-> (slurp (resource "puppetlabs/puppetdb/cli/export/tiny-catalog.json"))
      json/parse-string
      keywordize-keys
      (assoc :certname "foo.local")))

;; Tests without corrective changes support

(deftest-http-app query-event-counts
  [[_version endpoint] endpoints
   method [:get :post]]

  (without-corrective-change
    (store-example-report! (:basic reports) (now))
    (let [count1 {:subject_type "containing_class" :subject {:title nil}
                  :failures 1 :successes 1 :noops 0 :skips 0}
          count2 {:subject_type "containing_class" :subject {:title "Foo"}
                  :failures 0 :successes 0 :noops 0 :skips 1}]

    (testing "summarize_by rejects unsupported values"
      (let [{:keys [body status headers]}
            (query-response method endpoint
                            ["=" "certname" "foo.local"]
                            {:summarize_by "illegal-summarize-by"})]
        (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
        (are-error-response-headers headers)
        (is (re-find #"Unsupported value for 'summarize_by': 'illegal-summarize-by'"
                     body))))

    (testing "paging"
      (testing "limit works"
        (doseq [[limit expected] [[1 1] [2 2] [3 3] [100 3]]]
          (let [result (query-result method endpoint nil {:limit limit :summarize_by "resource"})]
            (is (= expected (count result))))))

      (testing "offset works"
        (doseq [[offset expected] [[0 3] [1 2] [2 1] [3 0]]]
          (let [result (query-result method endpoint nil {:offset offset
                                                          :summarize_by "resource"})]
            (is (= expected (count result))))))

      (testing "order_by rejects invalid fields"
        (let [{:keys [status body headers]}
              (query-response
               method endpoint
               nil
               {:summarize_by "certname"
                :order_by "invalid"})]
          (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
          (are-error-response-headers headers)
          (is (re-find #"Illegal value 'invalid' for :order_by" body))))

      (testing "numerical fields"
        (doseq [[order expected] [["asc"  [count2 count1]]
                                  ["desc" [count1 count2]]]]
          (testing order
            (let [actual (ordered-query-result
                           method endpoint
                           ["=" "certname" "foo.local"]
                           {:summarize_by "containing_class"
                            :order_by (vector-param
                                        method [{"field" "successes"
                                                 "order" order}])})]
              (is (= expected actual)))))))

    (testing "count_by"
      (testing "count_by rejects unsupported values"
        (let [{:keys [status body headers]}
              (query-response
               method endpoint
               ["=" "certname" "foo.local"]
               {:summarize_by "certname"
                :count_by "illegal-count-by"})]
          (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
          (are-error-response-headers headers)
          (is (re-find #"Unsupported value for 'count_by': 'illegal-count-by'"
                       body))))

      (testing "resource"
        (let [expected #{{:failures 0
                          :successes 0
                          :noops 0
                          :skips 1
                          :subject_type "containing_class"
                          :subject {:title "Foo"}}
                         {:failures 1
                          :successes 1
                          :noops 0
                          :skips 0
                          :subject_type "containing_class"
                          :subject {:title nil}}}
              actual (query-result method endpoint ["=" "certname" "foo.local"]
                                   {:summarize_by "containing_class"
                                    :count_by "resource"})]
          (is (= expected actual))))

      (testing "certname"
        (let [expected #{{:failures 0
                          :successes 0
                          :noops 0
                          :skips 1
                          :subject_type "containing_class"
                          :subject {:title "Foo"}}
                         {:failures 1
                          :successes 1
                          :noops 0
                          :skips 0
                          :subject_type "containing_class"
                          :subject {:title nil}}}
              actual (query-result method endpoint ["~" "certname" ".*"]
                                   {:summarize_by "containing_class"
                                    :count_by "certname"})]
          (is (= expected actual)))))

  (testing "nontrivial query using all the optional parameters"
    (let [expected #{{:subject_type "containing_class"
                      :subject {:title "Foo"}
                      :failures 0
                      :successes 0
                      :noops 0
                      :skips 1}}
          actual (query-result
                  method endpoint
                  ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                  {:summarize_by "containing_class"
                   :count_by      "certname"
                   :counts_filter (vector-param method ["<" "successes" 1])})]
      (is (= expected actual)))))

  (testing "counts_filter"
    (testing "= operator"
      (let [expected #{{:failures 0
                        :successes 1
                        :noops 0
                        :skips 0
                        :subject_type "resource"
                        :subject {:type "Notify" :title "notify, yo"}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "resource"
                                  :counts_filter (vector-param method ["=" "successes" 1])})]
        (is (= expected actual))))

    (testing "> operator"
      (let [expected #{{:failures 1
                        :successes 1
                        :noops 0
                        :skips 0
                        :subject_type "containing_class"
                        :subject  {:title nil}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "containing_class"
                                  :counts_filter (vector-param method [">" "successes" 0])})]
        (is (= expected actual))))

    (testing ">= operator"
      (let [expected #{{:failures 1
                        :successes 1
                        :noops 0
                        :skips 1
                        :subject_type "certname"
                        :subject {:title "foo.local"}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "certname"
                                  :counts_filter (vector-param method [">=" "successes" 1])})]
        (is (= expected actual))))

    (testing "< operator"
      (let [expected #{{:failures 0
                        :successes 0
                        :noops 0
                        :skips 1
                        :subject_type "resource"
                        :subject {:type "Notify"
                                  :title "hi"}}
                       {:failures 1
                        :noops 0
                        :skips 0
                        :subject {:title "notify, yar"
                                  :type  "Notify"}
                        :subject_type "resource"
                        :successes 0}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "resource"
                                  :counts_filter (vector-param method ["<" "successes" 1])})]
        (is (= expected actual))))

    (testing "<= operator"
      (let [expected #{{:failures 0
                        :successes 1
                        :noops 0
                        :skips 0
                        :subject_type "resource"
                        :subject {:type "Notify" :title "notify, yo"}}
                       {:failures 1
                        :successes 0
                        :noops 0
                        :skips 0
                        :subject_type "resource"
                        :subject {:type "Notify" :title "notify, yar"}}
                       {:failures 0
                        :successes 0
                        :noops 0
                        :skips 1
                        :subject_type "resource"
                        :subject {:type "Notify" :title "hi"}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "resource"
                                  :counts_filter (vector-param method ["<=" "successes" 1])})]
        (is (= expected actual)))))

  (doseq [[label count?] [["without" false]
                          ["with" true]]]
    (testing (str "should support paging through event-counts " label " counts")
      (let [expected  [{:subject_type "resource"
                        :subject {:type "Notify" :title "hi"}
                        :failures              0
                        :successes             0
                        :noops                 0
                        :skips                 1}
                       {:subject_type "resource"
                        :subject {:type "Notify" :title "notify, yar"}
                        :failures              1
                        :successes             0
                        :noops                 0
                        :skips                 0}
                       {:subject_type "resource"
                        :subject {:type "Notify" :title "notify, yo"}
                        :failures              0
                        :successes             1
                        :noops                 0
                        :skips                 0}]
            {:keys [headers body] :as res}
            (query-response method
                            endpoint
                            [">" "timestamp" 0]
                            {:summarize_by "resource"
                             :order_by (vector-param method [{"field" "resource_title"}])
                             :include_total count?})
            _ (tu/assert-success! res)
            body (tu/parse-result body)]
        (when count?
          (is (= "3" (get headers "X-Records"))))
        (is (= (count expected) (count body)))
        (is (= expected body)))))))

(deftest-http-app query-distinct-event-counts
  [[_version endpoint] endpoints
   method [:get :post]]

  (without-corrective-change
    (store-example-report! (:basic reports) (now))
    (store-example-report! (:basic3 reports) (now))
    (testcat/replace-catalog example-catalog)
    (testing "should only count the most recent event for each resource"
      (are [query result]
           (is (= result
                  (query-result method endpoint query
                                {:summarize_by "resource"
                                 :distinct_resources true
                                 :distinct_start_time 0
                                 :distinct_end_time (now)})))

           ["=" "latest_report?" true]
           #{{:failures 0
              :successes 1
              :noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify"
                        :title "notify, yo"}}
             {:failures 1
              :successes 0
              :noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify"
                        :title "notify, yar"}}
             {:failures 0
              :successes 0
              :noops 0
              :skips 1
              :subject_type "resource"
              :subject {:type "Notify"
                        :title "hi"}}}

           ["=" "latest_report?" false]
           #{{:failures 0,
              :noops 0,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource",
              :successes 1}
             {:failures 0,
              :noops 0,
              :skips 1,
              :subject {:title "hi", :type "Notify"},
              :subject_type "resource",
              :successes 0}
             {:failures 1,
              :noops 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource",
              :successes 0}}

           ["=" "certname" "foo.local"]
           #{{:subject_type "resource"
              :subject {:type "Notify" :title "notify, yo"}
              :failures 0
              :successes 1
              :noops 0
              :skips 0}
             {:subject_type "resource"
              :subject {:type "Notify" :title "notify, yar"}
              :failures 1
              :successes 0
              :noops 0
              :skips 0}
             {:subject_type "resource"
              :subject {:type "Notify" :title "hi"}
              :failures 0
              :successes 0
              :noops 0
              :skips 1}}

           nil
           #{{:subject_type "resource"
              :subject {:type "Notify" :title "notify, yo"}
              :failures 0
              :successes 1
              :noops 0
              :skips 0}
             {:subject_type "resource"
              :subject {:type "Notify" :title "notify, yar"}
              :failures 1
              :successes 0
              :noops 0
              :skips 0}
             {:subject_type "resource"
              :subject {:type "Notify" :title "hi"}
              :failures 0
              :successes 0
              :noops 0
              :skips 1}}

           ["~" "certname" ".*"]
           #{{:subject_type "resource"
              :subject {:type "Notify" :title "notify, yo"}
              :failures 0
              :successes 1
              :noops 0
              :skips 0}
             {:subject_type "resource"
              :subject {:type "Notify" :title "notify, yar"}
              :failures 1
              :successes 0
              :noops 0
              :skips 0}
             {:subject_type "resource"
              :subject {:type "Notify" :title "hi"}
              :failures 0
              :successes 0
              :noops 0
              :skips 1}}

           ["~" "environment" ".*"]
           #{{:subject_type "resource"
              :subject {:type "Notify" :title "notify, yo"}
              :failures 0
              :successes 1
              :noops 0
              :skips 0}
             {:subject_type "resource"
              :subject {:type "Notify" :title "notify, yar"}
              :failures 1
              :successes 0
              :noops 0
              :skips 0}
             {:subject_type "resource"
              :subject {:type "Notify" :title "hi"}
              :failures 0
              :successes 0
              :noops 0
              :skips 1}}

           ["~" "property" ".*"]
           #{{:failures 0
              :successes 1
              :noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify" :title "notify, yo"}}
             {:failures 1
              :successes 0
              :noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify" :title "notify, yar"}}}

           ["in" "certname" ["extract" "certname"
                             ["select_resources" ["~" "certname" ".*"]]]]
           #{{:failures 0
              :successes 1
              :noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify" :title "notify, yo"}}
             {:failures 1
              :successes 0
              :noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify" :title "notify, yar"}}
             {:failures 0
              :successes 0
              :noops 0
              :skips 1
              :subject_type "resource"
              :subject {:type "Notify" :title "hi"}}}

           ["in" "certname" ["extract" "certname"
                             ["select_resources" ["~" "tag" ".*"]]]]
           #{{:failures 0
              :successes 1
              :noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify" :title "notify, yo"}}
             {:failures 1
              :successes 0
              :noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify" :title "notify, yar"}}
             {:failures 0
              :successes 0
              :noops 0
              :skips 1
              :subject_type "resource"
              :subject {:type "Notify" :title "hi"}}}))))

(deftest-http-app query-with-environment
  [[_version endpoint] endpoints
   method [:get :post]]

  (without-corrective-change
    (store-example-report! (:basic reports) (now))
    (store-example-report! (assoc (:basic2 reports)
                             :certname "bar.local"
                             :environment "PROD") (now))
    (are [result query] (is (= (query-result method endpoint query
                                             {:summarize_by "resource"})
                               result))
         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}
           {:subject_type "resource",
            :noops 0,
            :skips 0,
            :successes 1,
            :failures 0,
            :subject {:type "File", :title "tmp-directory"}}
           {:subject_type "resource",
            :noops 0,
            :skips 0,
            :successes 1,
            :failures 0,
            :subject {:type "File", :title "puppet-managed-file"}}
           {:subject_type "resource",
            :noops 0,
            :skips 0,
            :successes 1,
            :failures 0,
            :subject
            {:type "Notify", :title "Creating tmp directory at /Users/foo/tmp"}}}
         nil

         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}}
         ["=" "environment" "DEV"]

         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}}
         ["~" "environment" "DE.*"]

         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}}
         ["not" ["=" "environment" "PROD"]]

         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}
           {:subject_type "resource",
            :noops 0,
            :skips 0,
            :successes 1,
            :failures 0,
            :subject {:type "File", :title "tmp-directory"}}
           {:subject_type "resource",
            :noops 0,
            :skips 0,
            :successes 1,
            :failures 0,
            :subject {:type "File", :title "puppet-managed-file"}}
           {:subject_type "resource",
            :noops 0,
            :skips 0,
            :successes 1,
            :failures 0,
            :subject
            {:type "Notify", :title "Creating tmp directory at /Users/foo/tmp"}}}
         ["OR"
          ["=" "environment" "PROD"]
          ["=" "environment" "DEV"]])))

;; Tests with corrective changes support

(deftest-http-app query-event-counts-with-corrective-changes
  [[_version endpoint] endpoints
   method [:get :post]]

  (with-corrective-change
    (store-example-report! (:basic reports) (now))
    (let [count1 {:subject_type "containing_class"
                  :subject {:title nil}
                  :failures 1
                  :intentional_successes 0
                  :corrective_successes 1
                  :intentional_noops 0
                  :corrective_noops 0
                  :skips 0}
          count2 {:subject_type "containing_class"
                  :subject {:title "Foo"}
                  :failures 0
                  :intentional_successes 0
                  :corrective_successes 0
                  :intentional_noops 0
                  :corrective_noops 0
                  :skips 1}]

      (testing "numerical fields"
        (doseq [[order expected] [["asc"  [count2 count1]]
                                  ["desc" [count1 count2]]]]
          (testing order
            (let [actual (ordered-query-result
                           method endpoint
                           ["=" "certname" "foo.local"]
                           {:summarize_by "containing_class"
                            :order_by (vector-param
                                        method [{"field" "corrective_successes"
                                                 "order" order}])})]
              (is (= expected actual)))))))

    (testing "count_by"
      (testing "resource"
        (let [expected #{{:failures 0
                          :intentional_successes 0
                          :corrective_successes 0
                          :intentional_noops 0
                          :corrective_noops 0
                          :skips 1
                          :subject_type "containing_class"
                          :subject {:title "Foo"}}
                         {:failures 1
                          :intentional_successes 0
                          :corrective_successes 1
                          :intentional_noops 0
                          :corrective_noops 0
                          :skips 0
                          :subject_type "containing_class"
                          :subject {:title nil}}}
              actual (query-result method endpoint ["=" "certname" "foo.local"]
                                   {:summarize_by "containing_class"
                                    :count_by "resource"})]
          (is (= expected actual))))

      (testing "certname"
        (let [expected #{{:failures 0
                          :intentional_successes 0
                          :corrective_successes 0
                          :intentional_noops 0
                          :corrective_noops 0
                          :skips 1
                          :subject_type "containing_class"
                          :subject {:title "Foo"}}
                         {:failures 1
                          :intentional_successes 0
                          :corrective_successes 1
                          :intentional_noops 0
                          :corrective_noops 0
                          :skips 0
                          :subject_type "containing_class"
                          :subject {:title nil}}}
              actual (query-result method endpoint ["~" "certname" ".*"]
                                   {:summarize_by "containing_class"
                                    :count_by "certname"})]
          (is (= expected actual)))))

  (testing "nontrivial query using all the optional parameters"
    (let [expected  #{{:subject_type "containing_class"
                       :subject {:title "Foo"}
                       :failures 0
                       :intentional_successes 0
                       :corrective_successes 0
                       :intentional_noops 0
                       :corrective_noops 0
                       :skips 1}
                      {:corrective_noops 0
                       :corrective_successes 1
                       :failures 0
                       :intentional_noops 0
                       :intentional_successes 0
                       :skips 0
                       :subject {:title nil}
                       :subject_type "containing_class"}}
          response  (query-result
                      method endpoint
                      ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                      {:summarize_by "containing_class"
                       :count_by      "certname"
                       :counts_filter (vector-param method ["<" "intentional_successes" 1])})]
      (is (= expected response))))

  (testing "counts_filter"
    (testing "= operator"
      (let [expected #{{:failures 0
                        :intentional_successes 0
                        :corrective_successes 1
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 0
                        :subject_type "resource"
                        :subject  {:type "Notify" :title "notify, yo"}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "resource"
                                  :counts_filter (vector-param method ["=" "corrective_successes" 1])})]
        (is (= expected actual))))

    (testing "> operator"
      (let [expected #{{:failures 1
                        :intentional_successes 0
                        :corrective_successes 1
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 0
                        :subject_type "containing_class"
                        :subject  {:title nil}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "containing_class"
                                  :counts_filter (vector-param method [">" "corrective_successes" 0])})]
        (is (= expected actual))))

    (testing ">= operator"
      (let [expected #{{:failures 1
                        :intentional_successes 0
                        :corrective_successes 1
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 1
                        :subject_type "certname"
                        :subject {:title "foo.local"}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "certname"
                                  :counts_filter (vector-param method [">=" "corrective_successes" 1])})]
        (is (= expected actual))))

    (testing "< operator"
      (let [expected #{{:failures 0
                        :intentional_successes 0
                        :corrective_successes 0
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 1
                        :subject_type "resource"
                        :subject {:type "Notify"
                                  :title "hi"}}
                       {:corrective_noops      0
                        :corrective_successes  0
                        :failures              1
                        :intentional_noops     0
                        :intentional_successes 0
                        :skips                 0
                        :subject               {:title "notify, yar"
                                                :type  "Notify"}
                        :subject_type          "resource"}
                       {:failures 0
                        :intentional_successes 0
                        :corrective_successes 1
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 0
                        :subject_type "resource"
                        :subject {:type "Notify"
                                  :title "notify, yo"}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "resource"
                                  :counts_filter (vector-param method ["<" "intentional_successes" 1])})]
        (is (= expected actual))))

    (testing "<= operator"
      (let [expected #{{:failures 0
                        :intentional_successes 0
                        :corrective_successes 1
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 0
                        :subject_type "resource"
                        :subject {:type "Notify" :title "notify, yo"}}
                       {:failures 1
                        :intentional_successes 0
                        :corrective_successes 0
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 0
                        :subject_type "resource"
                        :subject {:type "Notify" :title "notify, yar"}}
                       {:failures 0
                        :intentional_successes 0
                        :corrective_successes 0
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 1
                        :subject_type "resource"
                        :subject {:type "Notify" :title "hi"}}}
            actual (query-result method endpoint
                                 ["~" "certname" ".*"]
                                 {:summarize_by "resource"
                                  :counts_filter (vector-param method ["<=" "intentional_successes" 1])})]
        (is (= expected actual)))))

  (doseq [[label count?] [["without" false]
                          ["with" true]]]
    (testing (str "should support paging through event-counts " label " counts")
      (let [expected  [{:subject_type "resource"
                        :subject {:type "Notify" :title "hi"}
                        :failures              0
                        :intentional_successes 0
                        :corrective_successes  0
                        :intentional_noops     0
                        :corrective_noops      0
                        :skips                 1}
                       {:subject_type "resource"
                        :subject {:type "Notify" :title "notify, yar"}
                        :failures              1
                        :intentional_successes 0
                        :corrective_successes  0
                        :intentional_noops     0
                        :corrective_noops      0
                        :skips                 0}
                       {:subject_type "resource"
                        :subject {:type "Notify" :title "notify, yo"}
                        :failures              0
                        :intentional_successes 0
                        :corrective_successes  1
                        :intentional_noops     0
                        :corrective_noops      0
                        :skips                 0}]
            {:keys [headers body] :as res}
            (query-response method
                            endpoint
                            [">" "timestamp" 0]
                            {:summarize_by "resource"
                             :order_by (vector-param method [{"field" "resource_title"}])
                             :include_total count?})
            _ (tu/assert-success! res)
            body (tu/parse-result body)]
        (when count?
          (is (= "3" (get headers "X-Records"))))
        (is (= (count expected) (count body)))
        (is (= expected body)))))))

(deftest-http-app query-distinct-event-counts-with-corrective-changes
  [[_version endpoint] endpoints
   method [:get :post]]

  (with-corrective-change
    (store-example-report! (:basic reports) (now))
    (store-example-report! (:basic3 reports) (now))
    (testcat/replace-catalog example-catalog)
    (testing "should only count the most recent event for each resource"
      (are [query result]
           (is (= result
                  (query-result method endpoint query
                                {:summarize_by "resource"
                                 :distinct_resources true
                                 :distinct_start_time 0
                                 :distinct_end_time (now)})))


           ["=" "latest_report?" true]
           #{{:failures 0
              :intentional_successes 1
              :corrective_successes 0
              :intentional_noops 0
              :corrective_noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify"
                        :title "notify, yo"}}
             {:failures 1
              :intentional_successes 0
              :corrective_successes 0
              :intentional_noops 0
              :corrective_noops 0
              :skips 0
              :subject_type "resource"
              :subject {:type "Notify"
                        :title "notify, yar"}}
             {:failures 0
              :intentional_successes 0
              :corrective_successes 0
              :intentional_noops 0
              :corrective_noops 0
              :skips 1
              :subject_type "resource"
              :subject {:type "Notify"
                        :title "hi"}}}

           ["=" "latest_report?" false]
           #{{:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 1,
              :subject {:title "hi", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 1,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 1,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource"}}

           ["=" "certname" "foo.local"]
           #{{:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 1,
              :subject {:title "hi", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 1,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 1,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource"}}

           nil
           #{{:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 1,
              :subject {:title "hi", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 1,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 1,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource"}}

           ["~" "certname" ".*"]
           #{{:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 1,
              :subject {:title "hi", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 1,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 1,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource"}}

           ["~" "environment" ".*"]
           #{{:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 1,
              :subject {:title "hi", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 1,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 1,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource"}}

           ["~" "property" ".*"]
           #{{:corrective_noops 0,
              :corrective_successes 0,
              :failures 1,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 1,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource"}}

           ["in" "certname" ["extract" "certname"
                             ["select_resources" ["~" "certname" ".*"]]]]
           #{{:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 1,
              :subject {:title "hi", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 1,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 1,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource"}}

           ["in" "certname" ["extract" "certname"
                             ["select_resources" ["~" "tag" ".*"]]]]
           #{{:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 1,
              :subject {:title "hi", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 1,
              :intentional_noops 0,
              :intentional_successes 0,
              :skips 0,
              :subject {:title "notify, yar", :type "Notify"},
              :subject_type "resource"}
             {:corrective_noops 0,
              :corrective_successes 0,
              :failures 0,
              :intentional_noops 0,
              :intentional_successes 1,
              :skips 0,
              :subject {:title "notify, yo", :type "Notify"},
              :subject_type "resource"}}))))

(deftest-http-app query-with-environment-with-corrective-changes
  [[_version endpoint] endpoints
   method [:get :post]]

  (with-corrective-change
    (store-example-report! (:basic reports) (now))
    (store-example-report! (assoc (:basic2 reports)
                             :certname "bar.local"
                             :environment "PROD") (now))
    (are [result query] (is (= result
                               (query-result method endpoint query
                                             {:summarize_by "resource"})))
         #{{:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 1,
            :subject {:title "hi", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 1,
            :skips 0,
            :subject {:title "Creating tmp directory at /Users/foo/tmp", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 1,
            :skips 0,
            :subject {:title "puppet-managed-file", :type "File"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 1,
            :skips 0,
            :subject {:title "tmp-directory", :type "File"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 1,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yar", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 1,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yo", :type "Notify"},
            :subject_type "resource"}}
         nil

         #{{:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 1,
            :subject {:title "hi", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 1,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yar", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 1,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yo", :type "Notify"},
            :subject_type "resource"}}
         ["=" "environment" "DEV"]

         #{{:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 1,
            :subject {:title "hi", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 1,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yar", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 1,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yo", :type "Notify"},
            :subject_type "resource"}}
         ["~" "environment" "DE.*"]

         #{{:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 1,
            :subject {:title "hi", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 1,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yar", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 1,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yo", :type "Notify"},
            :subject_type "resource"}}
         ["not" ["=" "environment" "PROD"]]

         #{{:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 1,
            :subject {:title "hi", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 1,
            :skips 0,
            :subject {:title "Creating tmp directory at /Users/foo/tmp", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 1,
            :skips 0,
            :subject {:title "puppet-managed-file", :type "File"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 1,
            :skips 0,
            :subject {:title "tmp-directory", :type "File"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 0,
            :failures 1,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yar", :type "Notify"},
            :subject_type "resource"}
           {:corrective_noops 0,
            :corrective_successes 1,
            :failures 0,
            :intentional_noops 0,
            :intentional_successes 0,
            :skips 0,
            :subject {:title "notify, yo", :type "Notify"},
            :subject_type "resource"}}
         ["OR"
          ["=" "environment" "PROD"]
          ["=" "environment" "DEV"]])))
