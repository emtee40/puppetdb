(ns puppetlabs.puppetdb.http.paging-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [get-request]]
            [puppetlabs.puppetdb.testutils.http
             :refer [*app*
                     are-error-response-headers
                     deftest-http-app]])
  (:import
   (java.net HttpURLConnection)))

(def versions [:v4])

(def types ["events"
            "fact-names"
            "facts"
            "nodes"
            "reports"])

(deftest-http-app paging-options
  [version versions
   type types
   :let [endpoint (str "/" (name version) "/" type)]]

  (testing "'order_by' should properly handle malformed JSON input"
    (let [malformed-JSON  "[{\"field\":\"status\" \"order\":\"DESC\"}]"
          response        (*app* (get-request endpoint
                                              ["these" "are" "unused"]
                                              {:order_by malformed-JSON}))
          body            (get response :body "null")]
      (is (= HttpURLConnection/HTTP_BAD_REQUEST (:status response)))
      (are-error-response-headers (:headers response))
      (is (re-find #"Illegal value '.*' for :order_by; expected a JSON array of maps" body))))

  (testing "'limit' should only accept positive non-zero integers"
    (doseq [invalid-limit [0
                           -1
                           1.1
                           "\"1\""
                           "\"abc\""]]
      (let [response  (*app* (get-request endpoint
                                          ["these" "are" "unused"]
                                          {:limit invalid-limit}))
            body      (get response :body "null")]
        (is (= HttpURLConnection/HTTP_BAD_REQUEST (:status response)))
        (are-error-response-headers (:headers response))
        (is (re-find #"Illegal value '.*' for :limit; expected a positive non-zero integer" body)))))

  (testing "'explain' should only accept `analyze` string"
    (doseq [invalid-explain [:somekeyword
                             :analyze
                             "some_invalid_string"
                             ""
                             123]]
      (let [response  (*app* (get-request endpoint
                                          ["these" "are" "unused"]
                                          {:explain invalid-explain}))
            body      (get response :body "null")]
        (is (= HttpURLConnection/HTTP_BAD_REQUEST (:status response)))
        (are-error-response-headers (:headers response))
        (is (re-find #"Illegal value '.*' for :explain; expected `analyze`." body)))))

  (testing "'offset' should only accept positive integers"
    (doseq [invalid-offset [-1
                            1.1
                            "\"1\""
                            "\"abc\""]]
      (let [response  (*app* (get-request endpoint
                                          ["these" "are" "unused"]
                                          {:offset invalid-offset}))
            body      (get response :body "null")]
        (is (= HttpURLConnection/HTTP_BAD_REQUEST (:status response)))
        (are-error-response-headers (:headers response))
        (is (re-find #"Illegal value '.*' for :offset; expected a non-negative integer" body)))))

  (testing "'order_by' :order should only accept nil, 'asc', or 'desc' (case-insensitive)"
    (doseq [invalid-order-by [[{"field" "foo"
                                "order" "foo"}]
                              [{"field" "foo"
                                "order" 1}]]]
      (let [response  (*app* (get-request endpoint
                                          ["these" "are" "unused"]
                                          {:order_by (json/generate-string invalid-order-by)}))
            body      (get response :body "null")]
        (is (= HttpURLConnection/HTTP_BAD_REQUEST (:status response)))
        (are-error-response-headers (:headers response))
        (is (re-find #"Illegal value '.*' in :order_by; 'order' must be either 'asc' or 'desc'" body))))))
