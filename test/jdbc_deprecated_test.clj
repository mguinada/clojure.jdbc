(ns jdbc-deprecated-test
  (:import org.postgresql.util.PGobject)
  (:require [jdbc.core :as jdbc]
            [jdbc.transaction :as tx]
            [jdbc.types :refer :all]
            [jdbc.impl :refer :all]
            [jdbc.proto :as proto]
            [hikari-cp.core :as hikari]
            [cheshire.core :as json]
            [clojure.test :refer :all]))

(def h2-dbspec1 {:classname "org.h2.Driver"
                 :subprotocol "h2"
                 :subname "jdbctest.db"})

(def h2-dbspec2 {:subprotocol "h2"
                 :subname "jdbctest.db"})

(def h2-dbspec3 {:subprotocol "h2"
                 :subname "mem:"})

(def h2-dbspec4 {:subprotocol "h2"
                 :subname "mem:"
                 :isolation-level :serializable})

(def pg-dbspec {:subprotocol "postgresql"
                :subname "//localhost:5432/test"})

(def pg-dbspec-pretty {:vendor "postgresql"
                       :name "test"
                       :host "localhost"
                       :read-only true})

(deftest datasource-spec
  (testing "Connection pool testing."
    (let [ds (hikari/make-datasource {:adapter "h2"
                                      :url "jdbc:h2:/tmp/test"})]
      (is (instance? javax.sql.DataSource ds))
      (with-open [conn (jdbc/connection ds)]
        (is (satisfies? proto/IConnection conn))
        (is (instance? java.sql.Connection (proto/connection conn)))
        (let [result (jdbc/query conn ["SELECT 1 + 1 as foo;"])]
          (is (= [{:foo 2}] result)))))))

(deftest db-extra-returning-keys
  (testing "Testing basic returning keys"
    (with-open [conn (jdbc/connection pg-dbspec)]
      (jdbc/execute! conn "DROP TABLE IF EXISTS foo_retkeys;")
      (jdbc/execute! conn "CREATE TABLE foo_retkeys (id int primary key, num integer);")
      (let [sql (str "INSERT INTO foo_retkeys (id, num) VALUES (?, ?)")
            res (jdbc/execute-prepared! conn sql [2, 0] [3, 0] {:returning [:id]})]
        (is (= res [{:id 2} {:id 3}])))))

  (testing "Testing returning keys with vector sql"
    (with-open [conn (jdbc/connection pg-dbspec)]
      (jdbc/execute! conn "DROP TABLE IF EXISTS foo_retkeys;")
      (jdbc/execute! conn "CREATE TABLE foo_retkeys (id int primary key, num integer);")
      (let [sql (str "INSERT INTO foo_retkeys (id, num) VALUES (?, ?)")
            res (jdbc/execute-prepared! conn [sql 2 0] {:returning [:id]})]
        (is (= res [{:id 2}])))))

  (testing "Testing wrong arguments"
    (with-open [conn (jdbc/connection pg-dbspec)]
      (is (thrown? IllegalArgumentException
                   (let [sql (str "INSERT INTO foo_retkeys (id, num) VALUES (?, ?)")]
                     (jdbc/execute-prepared! conn [sql 1 0] [2 0]))))))
)

(deftest db-specs
  (testing "Create connection with distinct dbspec"
    (let [c1 (jdbc/connection h2-dbspec1)
          c2 (jdbc/connection h2-dbspec2)
          c3 (jdbc/connection h2-dbspec3)
          c4 (jdbc/connection pg-dbspec-pretty)]
      (is (satisfies? proto/IConnection c1))
      (is (satisfies? proto/IConnection c2))
      (is (satisfies? proto/IConnection c3))
      (is (satisfies? proto/IConnection c4)))))

(deftest db-isolation-level
  (testing "Using dbspec with :isolation-level"
    (let [c1 (-> (jdbc/connection h2-dbspec4)
                 (proto/connection))
          c2 (-> (jdbc/connection h2-dbspec3)
                 (proto/connection))]
      (is (= (.getTransactionIsolation c1) 8))
      (is (= (.getTransactionIsolation c2) 2))))

  (testing "Check isolation level in transaction."
    (let [func1 (fn [conn]
                  (let [conn (proto/connection conn)
                        isolation (.getTransactionIsolation conn)]
                    (is (= isolation 8))))]
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (tx/call-in-transaction conn func1 {:isolation-level :serializable})))))

(deftest db-readonly-transactions
  (testing "Set readonly for transaction"
    (let [func (fn [conn]
                 (let [raw (proto/connection conn)]
                   (is (true? (.isReadOnly raw)))))]
      (with-open [conn (jdbc/connection pg-dbspec)]
        (tx/call-in-transaction conn func {:read-only true})
        (is (false? (.isReadOnly (proto/connection conn)))))))

  (testing "Set readonly flag with tx/with-transaction macro"
    (with-open [conn (jdbc/connection pg-dbspec)]
      (tx/with-transaction conn {:read-only true}
        (is (true? (.isReadOnly (proto/connection conn)))))
      (is (false? (.isReadOnly (proto/connection conn)))))))

(deftest db-commands
  (testing "Simple create table"
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (let [sql "CREATE TABLE foo (name varchar(255), age integer);"
            r   (jdbc/execute! conn sql)]
        (is (= (list 0) r)))))

  (testing "Create duplicate table"
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (let [sql "CREATE TABLE foo (name varchar(255), age integer);"]
        (jdbc/execute! conn sql)
        (is (thrown? org.h2.jdbc.JdbcBatchUpdateException (jdbc/execute! conn sql))))))

  (testing "Simple query result using query function"
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (let [result (jdbc/query conn ["SELECT 1 + 1 as foo;"])]
        (is (= [{:foo 2}] result)))))

  (testing "More complex query using query funcion"
    (with-open [conn (jdbc/connection pg-dbspec)]
      (let [result (jdbc/query conn ["SELECT * FROM generate_series(1, ?) LIMIT 1 OFFSET 3;" 10])]
        (is (= (count result) 1)))))

  (testing "Simple query result using query function overwriting identifiers parameter."
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (let [result (jdbc/query conn ["SELECT 1 + 1 as foo;"] {:identifiers identity})]
        (is (= [{:FOO 2}] result)))))

  (testing "Simple query result using query function and string parameter"
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (let [result (jdbc/query conn "SELECT 1 + 1 as foo;")]
        (is (= [{:foo 2}] result)))))

  (testing "Simple query result using query function as vectors of vectors"
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (let [result (jdbc/query conn ["SELECT 1 + 1 as foo;"] {:as-rows? true})]
        (is (= [2] (first result))))))

  (testing "Execute prepared"
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/execute! conn "CREATE TABLE foo (name varchar(255), age integer);")
      (jdbc/execute-prepared! conn "INSERT INTO foo (name,age) VALUES (?, ?);"
                         ["foo", 1]  ["bar", 2])

      (let [results (jdbc/query conn ["SELECT count(age) as total FROM foo;"])]
        (is (= [{:total 2}] results)))))

  (testing "Pass prepared statement."
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (let [stmt (jdbc/prepared-statement conn ["SELECT 1 + 1 as foo;"])
            result (jdbc/query conn stmt {:as-rows? true})]
        (is (= [2] (first result)))))))

(deftest lazy-queries
  (testing "Simple lazy query"
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (tx/with-transaction conn
        (with-open [cursor (jdbc/lazy-query conn ["SELECT 1 + 1 as foo;"])]
          (let [result (vec (jdbc/cursor->lazyseq cursor))]
            (is (= [{:foo 2}] result)))
          (let [result (vec (jdbc/cursor->lazyseq cursor))]
            (is (= [{:foo 2}] result))))))))

(deftest db-execute-prepared-statement
  (testing "Execute simple sql based prepared statement."
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/execute! conn "CREATE TABLE foo (name varchar(255), age integer);")
      (let [res (jdbc/execute-prepared! conn "INSERT INTO foo (name,age) VALUES (?, ?);"
                                   ["foo", 1]  ["bar", 2])]
        (is (= res (seq [1 1]))))))

  (testing "Executing self defined prepared statement"
    (with-open [conn (jdbc/connection h2-dbspec3)]
      (jdbc/execute! conn "CREATE TABLE foo (name varchar(255), age integer);")
      (let [stmt (jdbc/prepared-statement conn "INSERT INTO foo (name,age) VALUES (?, ?);")
            res1 (jdbc/execute-prepared! conn stmt ["foo", 1] ["bar", 2])
            res2 (jdbc/execute-prepared! conn stmt ["fooo", 1] ["barr", 2])
            results (jdbc/query conn ["SELECT count(age) as total FROM foo;"])]
        (is (= [{:total 4}] results))))))

(deftest db-commands-bytes
  (testing "Insert bytes"
    (let [buffer       (byte-array (map byte (range 0 10)))
          inputStream  (java.io.ByteArrayInputStream. buffer)
          sql          "CREATE TABLE foo (id integer, data bytea);"]
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (jdbc/execute! conn sql)
        (let [res (jdbc/execute-prepared! conn "INSERT INTO foo (id, data) VALUES (?, ?);" [1 inputStream])]
          (is (= res '(1))))

        (let [res (jdbc/query conn "SELECT * FROM foo")
              res (first res)]
          (is (instance? (Class/forName "[B") (:data res)))
          (is (= (get (:data res) 2) 2)))))))

(extend-protocol proto/ISQLType
  (class (into-array String []))

  (set-stmt-parameter! [this conn stmt index]
    (let [raw-conn        (proto/connection conn)
          prepared-value  (proto/as-sql-type this conn)
          array           (.createArrayOf raw-conn "text" prepared-value)]
      (.setArray stmt index array)))

  (as-sql-type [this conn] this))

(deftest db-commands-custom-types
  (testing "Test use arrays"
    (with-open [conn (jdbc/connection pg-dbspec)]
      (tx/with-transaction conn
        (tx/set-rollback! conn)
        (let [sql "CREATE TABLE arrayfoo (id integer, data text[]);"
              dat (into-array String ["foo", "bar"])]
          (jdbc/execute! conn sql)
          (let [res (jdbc/execute-prepared! conn "INSERT INTO arrayfoo (id, data) VALUES (?, ?);" [1, dat])]
            (is (= res '(1))))
          (let [res (first (jdbc/query conn "SELECT * FROM arrayfoo"))]

            (let [rr (.getArray (:data res))]
              (is (= (count rr) 2))
              (is (= (get rr 0) "foo"))
              (is (= (get rr 1) "bar")))))))))

(def basic-tx-strategy
  (reify
    tx/ITransactionStrategy
    (begin! [_ conn opts]
      (let [rconn (proto/connection conn)
            metadata (meta conn)
            depth    (:depth-level metadata)]
        (if depth
          (with-meta conn
            (assoc metadata :depth-level (inc depth)))

          (let [prev-autocommit (.getAutoCommit rconn)]
            (.setAutoCommit rconn false)
            (with-meta conn
              (assoc metadata
                     :depth-level 0
                     :prev-autocommit prev-autocommit))))))

    (rollback! [_ conn opts]
      (let [rconn (proto/connection conn)
            metadata (meta conn)
            depth    (:depth-level metadata)]
        (when (= depth 0)
          (.rollback rconn)
          (.setAutoCommit rconn (:prev-autocommit metadata)))))

    (commit! [_ conn opts]
      (let [rconn (proto/connection conn)
            metadata (meta conn)
            depth    (:depth-level metadata)]
        (when (= depth 0)
          (.commit rconn)
          (.setAutoCommit rconn (:prev-autocommit metadata)))))))

(def dummmy-tx-strategy
  (reify tx/ITransactionStrategy
    (begin! [_ conn opts] conn)
    (rollback! [_ conn opts] nil)
    (commit! [_ conn opts] nil)))

(deftest db-transaction-strategy
  (let [sql1 "CREATE TABLE foo (name varchar(255), age integer);"
        sql2 "INSERT INTO foo (name,age) VALUES (?, ?);"
        sql3 "SELECT age FROM foo;"]
    (testing "Test dummy transaction strategy"
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (tx/with-transaction-strategy conn dummmy-tx-strategy
          (is (identical? (:tx-strategy (meta conn))
                          dummmy-tx-strategy))
          (jdbc/execute! conn sql1)
          (try
            (tx/with-transaction conn
              (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
              (let [results (jdbc/query conn sql3)]
                (is (= (count results) 2))
                (throw (RuntimeException. "Fooo"))))
            (catch Exception e
              (let [results (jdbc/query conn sql3)]
                (is (= (count results) 2))))))))

    (testing "Test basic transaction strategy"
      (with-open [conn (-> (jdbc/connection h2-dbspec3)
                           (tx/wrap-transaction-strategy basic-tx-strategy))]
        (is (identical? (:tx-strategy (meta conn))
                        basic-tx-strategy))
        (jdbc/execute! conn sql1)
        (try
          (tx/with-transaction conn
            (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (is (= (:depth-level (meta conn)) 0))
            (tx/with-transaction conn
              (is (= (:depth-level (meta conn)) 1))
              (let [results (jdbc/query conn sql3)]
                (is (= (count results) 2))
                (throw (RuntimeException. "Fooo")))))
          (catch Exception e
            (let [results (jdbc/query conn sql3)]
              (is (= (count results) 0)))))))))


(deftest db-transactions
  (let [sql1 "CREATE TABLE foo (name varchar(255), age integer);"
        sql2 "INSERT INTO foo (name,age) VALUES (?, ?);"
        sql3 "SELECT age FROM foo;"]

    (testing "Basic transaction test with exception."
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (jdbc/execute! conn sql1)

        (try
          (tx/with-transaction conn
            (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (let [results (jdbc/query conn [sql3])]
              (is (= (count results) 2))
              (throw (RuntimeException. "Fooo"))))
          (catch Exception e
            (let [results (jdbc/query conn [sql3])]
              (is (= (count results) 0)))))))

    (testing "Basic transaction test without exception."
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (jdbc/execute! conn sql1)

        (tx/with-transaction conn
          (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2]))

        (tx/with-transaction conn
          (let [results (jdbc/query conn [sql3])]
            (is (= (count results) 2))))))

    (testing "Immutability"
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (tx/with-transaction conn
          (let [metadata (meta conn)]
            (is (:transaction metadata))
            (is (:rollback metadata))
            (is (false? @(:rollback metadata)))
            (is (nil? (:savepoint metadata)))))

        (let [metadata (meta conn)]
          (is (= (:transaction metadata) nil))
          (is (= (:rollback metadata) nil)))))

    (testing "Set savepoint"
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (tx/with-transaction conn
          (is (:transaction (meta conn)))
          (tx/with-transaction conn
            (is (not (nil? (:savepoint (meta conn)))))))))

    (testing "Set rollback 01"
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (jdbc/execute! conn sql1)

        (tx/with-transaction conn
          (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
          (is (false? @(:rollback (meta conn))))

          (tx/with-transaction conn
            (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (tx/set-rollback! conn)
            (is (true? @(:rollback (meta conn))))
            (let [results (jdbc/query conn sql3)]
              (is (= (count results) 4))))

          (let [results (jdbc/query conn [sql3])]
            (is (= (count results) 2))))))

    (testing "Set rollback 02"
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (jdbc/execute! conn sql1)

        (tx/with-transaction conn
          (tx/set-rollback! conn)
          (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])

          (is (true? @(:rollback (meta conn))))

          (tx/with-transaction conn
            (is (false? @(:rollback (meta conn))))

            (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
            (let [results (jdbc/query conn sql3)]
              (is (= (count results) 4))))

          (let [results (jdbc/query conn [sql3])]
            (is (= (count results) 4))))

        (let [results (jdbc/query conn [sql3])]
          (is (= (count results) 0)))))

    (testing "Subtransactions"
      (with-open [conn (jdbc/connection h2-dbspec3)]
        (jdbc/execute! conn sql1)

        (tx/with-transaction conn
          (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])

          (try
            (tx/with-transaction conn
              (jdbc/execute-prepared! conn sql2 ["foo", 1]  ["bar", 2])
              (let [results (jdbc/query conn [sql3])]
                (is (= (count results) 4))
                (throw (RuntimeException. "Fooo"))))
            (catch Exception e
              (let [results (jdbc/query conn [sql3])]
                (is (= (count results) 2))))))))
))

;; PostgreSQL json support

(extend-protocol proto/ISQLType
  clojure.lang.IPersistentMap
  (set-stmt-parameter! [self conn stmt index]
    (.setObject stmt index (proto/as-sql-type self conn)))
  (as-sql-type [self conn]
    (doto (PGobject.)
      (.setType "json")
      (.setValue (json/generate-string self)))))

(extend-protocol proto/ISQLResultSetReadColumn
  PGobject
  (from-sql-type [pgobj conn metadata i]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (json/parse-string value)
        :else value))))

(deftest db-postgresql-json-type
  (testing "Persist/Query json fields"
    (with-open [conn (jdbc/connection pg-dbspec)]
      (tx/with-transaction conn
        (tx/set-rollback! conn)
        (let [sql-create "CREATE TABLE jsontest (data json);"
              sql-query  "SELECT data FROM jsontest;"
              sql-insert "INSERT INTO jsontest (data) VALUES (?);"]
          (jdbc/execute! conn sql-create)
          (jdbc/execute-prepared! conn sql-insert [{:foo "bar"}])
          (let [res (first (jdbc/query conn sql-query))]
            (is (= res {:data {"foo" "bar"}}))))))))
