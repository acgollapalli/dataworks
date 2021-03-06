(defproject dataworks "0.5.0"
  :description "Stored function engine on top of crux and kafka for Web API's and stream processors"
  :url "https://github.com/acgollapalli/dataworks"
  :license {:name "Server Side Public License"
            :url "https://www.mongodb.com/licensing/server-side-public-license"}
  :dependencies [[org.clojure/clojure "1.10.2"]
                 [org.clojure/core.async "1.3.610"]
                 [clj-http "3.12.1"]
                 [yada "1.3.0-alpha9"]
                 [aleph "0.4.6"]
                 [bidi "2.1.6"]
                 [cheshire "5.10.0"]
                 [mount "0.1.16"]
                 [tick "0.4.30-alpha"]
                 [buddy/buddy-hashers "1.4.0"]
                 [juxt/crux-core "20.08-1.10.1-beta"]
                 [juxt/crux-kafka "20.08-1.10.1-beta"]
                 [juxt/crux-rocksdb "20.08-1.10.1-beta"]
                 [io.dropwizard.metrics/metrics-core "3.2.5"]
                 [camel-snake-kebab "0.4.2"]]
  :main ^:skip-aot dataworks.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[com.clojure-goes-fast/clj-memory-meter "0.1.2"]
                                  [juxt/crux-kafka-embedded "20.08-1.10.1-beta"]]
                   :jvm-opts ["-Djdk.attach.allowAttachSelf"] }})
