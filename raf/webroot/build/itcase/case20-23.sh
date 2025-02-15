#!/bin/sh
#
# Script to run unit test, invoked by jenkins
#


case20() {
  echo "########################"
  echo "Case 20: truncate store test"
  echo " 1. start two servers"
  echo " 2. insert data with partition store to server."
  echo " 3. insert data with replicated store to server."
  echo " 4. call it truncate data."
  echo " 5. verify whether data is clear."
  echo "########################"
  cd $WORKSPACE/idgs/
  find . -name "core" -exec rm -f {} \; 2>/dev/null

  echo "start 1 server"
  export idgs_public_port=7700
  export idgs_inner_port=7701
  export idgs_local_store=true
  dist/bin/idgs -c conf/cluster.conf  1>case20_server1.log 2>&1 &
  SRV_PID1=$!
  sleep 3

  echo "start 2 server"
  export idgs_public_port=8800
  export idgs_inner_port=8801
  dist/bin/idgs -c conf/cluster.conf  1>case20_server2.log 2>&1 &
  SRV_PID2=$!
  sleep 3

  echo "insert data with partition store to server."
  run_test $BUILD_DIR/target/itest/it_partition_store_insert_test 
  mv ut.log case20_step1.log

  echo "insert data with replicated store to server."
  run_test $BUILD_DIR/target/itest/it_replicated_store_test_insert 
  mv ut.log case20_step2.log
  
  sleep 1

  echo "truncate data."
  run_test $BUILD_DIR/target/itest/it_truncate_store_test
  mv ut.log case20_step3.log
  
  sleep 1

  echo "verify whether data is clear."
  run_test $BUILD_DIR/target/itest/it_store_data_not_found_test 
  mv ut.log case20_step4.log

  echo "kill servers."
  safekill $SRV_PID1
  safekill $SRV_PID2
  
  check_core_dump dist/bin/idgs
  #echo "########################"
}

case21() {
  echo "########################"
  echo "Case 21: test asynch tcp client timeout"
  echo " 1. start asynch tcp client"
  echo " 2. server hang up the actor message"
  echo " 3. client should be timeout for 5 seconds"
  echo "########################"
  cd $WORKSPACE/idgs/
  echo "start test asynch tcp client timeout"
  export idgs_public_port=7702
  export idgs_inner_port=8700
  run_test $BUILD_DIR/target/itest/it_asynch_client_timeout_test 
  
  check_core_dump dist/bin/idgs
  #echo "########################"
}

case22() {
  echo "########################"
  echo "Case 22: test asynch tcp client with 3 threads"
  echo "########################"
  cd $WORKSPACE/idgs/
  echo "start test asynch tcp client with 3 threads"
  export GLOG_v=0
  export idgs_public_port=7701
  export idgs_inner_port=8700
  run_test $BUILD_DIR/target/itest/it_asynch_client_multi_threads_test 
  
  check_core_dump dist/bin/idgs
  #echo "########################"
}

case23() {
  echo "########################"
  echo "Case 23: export action"
  echo " 1. start 2 servers"
  echo " 2. load tpch data, size 0.01"
  echo " 3. run test to save result to file"
  echo "########################"
  cd $WORKSPACE/idgs/
  export GLOG_v=0
  
  echo "starting server 1"
  export idgs_public_port=7700
  export idgs_inner_port=7701
  export idgs_local_store=true
  dist/bin/idgs -c conf/cluster.conf  1>case23_1.log 2>&1 &
  SRV_PID1=$!
  sleep 2

  echo "starting server 2"
  export idgs_public_port=8800
  export idgs_inner_port=8801
  dist/bin/idgs -c conf/cluster.conf  1>case23_2.log 2>&1 &
  SRV_PID2=$!
  sleep 2

  TPCH_SIZE="0.001"
  export TPCH_SIZE
  TPCH_HOME="/tmp/tpch_$TPCH_SIZE"
  export TPCH_HOME

  echo "generate tpch data"
  build/tpch-gen.sh
  

  echo "load tpch data"
  export idgs_public_port=9900
  export idgs_inner_port=9901
  export idgs_local_store=false
  dist/bin/idgs-load -s 1 -p $TPCH_HOME/dbgen -c conf/cluster.conf -m conf/tpch_file_mapper.conf -t 100 1>it_case23.log 2>&1
  
  sleep 1

  echo "run export action test"
  $BUILD_DIR/target/itest/it_export_action_test
  
  echo ""
  echo "============ RESULT ============"
  cat line_item_data_0.tbl
  echo "================================"

  echo "killing server 1"
  safekill $SRV_PID1 
  safekill $SRV_PID2
  
  rm line_item_data_* -rf
  
  # ensure kill all server
  check_core_dump dist/bin/idgs
  #echo "########################"
}