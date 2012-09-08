(ns util.storm
  "deploys and runs a Storm cluster in EC2"
  (:use util.supervise)
  (:use util.zookeeper)
  (:use util.ec2))

(def STORM_LOG "storm-install.log")
(def STORM_VERSION "0.8.0")

(def ZEROMQ_VERSION "2.1.4")

(defn install-storm [m {:keys [capture] :or {capture STORM_LOG}}]
  (when m
    (deploy-supervise m :capture capture) ;;make sure we have supervise deployed already
    (let [zmq (str "zeromq-" ZEROMQ_VERSION)
          storm (str "storm-" STORM_VERSION)]
      (command m "sudo yum install -y make" :capture capture)
      (command m "sudo yum install -y gcc-c++" :capture capture)
      (command m "sudo yum install -y libuuid-devel" :capture capture)
      (command m "sudo yum install -y git" :capture capture)
      (command m "sudo yum install -y libtool" :capture capture)
      (command m "sudo yum install -y java-1.6.0-openjdk-devel" :capture capture)
      (command m (format "curl -s -o %s.tar.gz http://download.zeromq.org/%s.tar.gz && echo downloaded" zmq zmq) :capture capture)
      (command m (format "tar xfz %s.tar.gz && echo untarred" zmq) :capture capture)
      (command m (format "(cd %s; ./autogen.sh && ./configure && make && sudo make install)" zmq) :capture capture)
      (command m "git clone https://github.com/nathanmarz/jzmq.git" :capture capture)
      (command m "(cd jzmq; ./autogen.sh && ./configure CFLAGS=\"-m64\" CXXFLAGS=\"-m64\" LDFLAGS=\"-m64\" && make && sudo make install)" :wait 180 :capture capture)
      (command m (format "curl -o %s.zip http://cloud.github.com/downloads/nathanmarz/storm/%s.zip" storm storm) :capture capture)
      (command m (format "unzip %s.zip && ln -s %s storm" storm storm) :capture STORM_LOG)))
  m)

(defn generate-storm-yaml [nimbus-ip zk-ip]
  (let [s (format "# Generated by proto.storm
storm.zookeeper.servers:
    - %s
nimbus.host: %s
drpc.servers:
    - %s
" zk-ip nimbus-ip nimbus-ip)]
    s))

(defn update-local-storm-conf [storm-yaml]
  (let [fname (str (get (System/getenv) "HOME") "/.storm/storm.yaml")]
    (with-open [w (clojure.java.io/writer fname)]
      (.write w storm-yaml))
    fname))

(defn run-storm [& {:keys [workers capture] :or {workers 2 capture STORM_LOG}}]
  (deploy-zookeeper :capture capture)
  (let [zk (deploy-zookeeper :capture capture)
        nimbus (install-storm (create-machine "nimbus" :type "m1.large"))
        workers (create-cluster "worker" workers :type "m1.large") ;;c1.xlarge is what storm-deploy uses. This should paramaterized
        storm-nodes (cons nimbus workers)
        cluster (cons zk storm-nodes)]
    (doall (map install-storm workers)) ;; use pmap? only if I handle merged log files bettter
    (let [remote-conf (generate-storm-yaml (:cluster-ip nimbus) (:cluster-ip zk))]
      (doall (map (fn [m] (write-remote-file m remote-conf "storm/conf/storm.yaml" :mode 0644)) storm-nodes)))
    
    (let [home (.trim (command nimbus "pwd" :capture :string))
          storm-home (format "%s/storm" home)]
      (supervise nimbus "storm-nimbus" (format "%s/bin/storm nimbus" storm-home) :capture capture)
      
      ;;(command nimbus "mkdir -p ui//supervisor && echo created ui directory" :capture capture)
      ;;(write-remote-file nimbus (storm-run-file "ui" "ui" home) "ui/run" :mode 0700)
      ;;(command nimbus "sudo nohup ui/run; sleep 1" :capture capture)
      (supervise nimbus "storm-ui" (format "%s/bin/storm ui" storm-home) :capture capture)
      
      (doall (map (fn [worker] ;; pmap?
                    ;;(command worker "mkdir -p worker/supervisor && echo created worker directory" :capture capture)
                    ;;(write-remote-file worker (storm-run-file "worker" "supervisor" home) "worker/run" :mode 0700)
                    ;;(command worker "sudo nohup worker/run; sleep 1" :capture capture)
                    (supervise worker "storm-supervisor" (format "%s/bin/storm supervisor" storm-home) :capture capture))
                  workers)))

    (update-local-storm-conf (generate-storm-yaml (:ip nimbus) (:ip zk))) ;;so our local storm client connects to this cluster
    
    cluster))

(defn storm-deployed? []
  (if (first (machines "nimbus")) true false))
  
(defn deploy-storm [& {:keys [workers capture] :or {workers 2 capture STORM_LOG}}]
  (let [m (first (machines "nimbus"))]
    (or m (run-storm :workers workers :capture capture))))

(defn stop-storm []  
  (destroy-cluster ZK_NAME)
  (destroy-cluster "nimbus")
  (destroy-cluster "worker")
  true)