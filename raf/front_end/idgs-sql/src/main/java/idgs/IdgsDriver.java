/*
Copyright (c) <2013>, Intel Corporation All Rights Reserved.

The source code, information and material ("Material") contained herein is owned by Intel Corporation or its suppliers or licensors, and title to such Material remains with Intel Corporation or its suppliers or licensors. The Material contains proprietary information of Intel or its suppliers and licensors. The Material is protected by worldwide copyright laws and treaty provisions. No part of the Material may be used, copied, reproduced, modified, published, uploaded, posted, transmitted, distributed or disclosed in any way without Intel's prior express written permission. No license under any patent, copyright or other intellectual property rights in the Material is granted to or conferred upon you, either expressly, by implication, inducement, estoppel or otherwise. Any license under such intellectual property rights must be express and approved by Intel in writing.

Unless otherwise agreed by Intel in writing, you may not remove or alter this notice or any other notice embedded in Materials by Intel or Intel’s suppliers or licensors in any way.
*/
package idgs;

import idgs.execution.IdgsLoad;
import idgs.execution.IdgsLoadTask;
import idgs.execution.IdgsTask;
import idgs.execution.IdgsWork;
import idgs.execution.RowData;
import idgs.execution.ResultSet;
import idgs.parse.IdgsSemanticAnalyzerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.QueryPlan;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.log.PerfLogger;
import org.apache.hadoop.hive.ql.metadata.AuthorizationException;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.AbstractSemanticAnalyzerHook;
import org.apache.hadoop.hive.ql.parse.BaseSemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.HiveSemanticAnalyzerHookContext;
import org.apache.hadoop.hive.ql.parse.HiveSemanticAnalyzerHookContextImpl;
import org.apache.hadoop.hive.ql.parse.ParseDriver;
import org.apache.hadoop.hive.ql.parse.ParseException;
import org.apache.hadoop.hive.ql.parse.ParseUtils;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.VariableSubstitution;
import org.apache.hadoop.hive.ql.plan.HiveOperation;
import org.apache.hadoop.hive.ql.session.SessionState;

public class IdgsDriver extends Driver {

  private QueryPlan plan;

  private Context context;

  private Schema schema;

  private String errorMessage;

  private HiveConf conf;

  private Field planField;

  private Field contextField;

  private Field schemaField;

  private Field errorMessageField;
  
  private Field maxRowsField;

  private Method doAuthMethod;

  private Method saHooksMethod;
  
  private long taskRunTime;
  
  private long compileTime;
  
  static private Log LOG;

  static {
    // set hive log dir
    String dir = System.getenv("IDGS_LOG_DIR");
    if (dir != null) {
      java.util.Properties sysp = new java.util.Properties(System.getProperties());
      sysp.setProperty("idgs.log.dir", dir);
      sysp.setProperty("hive.log.dir", dir);
      sysp.setProperty("hive.querylog.location", dir);
      sysp.setProperty("hive.exec.scratchdir", dir);
      System.setProperties(sysp);
    }
  }

  public IdgsDriver(HiveConf conf) {
    try {
      HiveConf.setBoolVar(conf, ConfVars.HIVEMAPSIDEAGGREGATE, false);
      HiveConf.setBoolVar(conf, ConfVars.METASTORE_SCHEMA_VERIFICATION, false);
      
      this.conf = conf;

      planField = Driver.class.getDeclaredField("plan");
      contextField = Driver.class.getDeclaredField("ctx");
      schemaField = Driver.class.getDeclaredField("schema");
      errorMessageField = Driver.class.getDeclaredField("errorMessage");
      maxRowsField = Driver.class.getDeclaredField("maxRows");
      Field logField = Driver.class.getDeclaredField("LOG");

      contextField.setAccessible(true);
      planField.setAccessible(true);
      schemaField.setAccessible(true);
      errorMessageField.setAccessible(true);
      logField.setAccessible(true);
      maxRowsField.setAccessible(true);

      plan = getPlan();
      context = (Context) contextField.get(this);
      schema = (Schema) schemaField.get(this);
      errorMessage = (String) errorMessageField.get(this);
      LOG = (Log) logField.get(this);

      doAuthMethod = Driver.class.getDeclaredMethod("doAuthorization", new Class<?>[] {BaseSemanticAnalyzer.class, String.class});
      doAuthMethod.setAccessible(true);
      saHooksMethod = Driver.class.getDeclaredMethod("getHooks", HiveConf.ConfVars.class, Class.class);
      saHooksMethod.setAccessible(true);

      // register task and work
      TaskFactory.taskvec.add(new TaskFactory.TaskTuple<IdgsWork>(IdgsWork.class, IdgsTask.class));
      TaskFactory.taskvec.add(new TaskFactory.TaskTuple<IdgsLoad>(IdgsLoad.class, IdgsLoadTask.class));
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  private static class QueryState {
    private HiveOperation op;
    private String cmd;
    private boolean init = false;

    /**
     * Initialize the queryState with the query state variables
     */
    public void init(HiveOperation op, String cmd) {
      this.op = op;
      this.cmd = cmd;
      this.init = true;
    }

    public boolean isInitialized() {
      return this.init;
    }

    public HiveOperation getOp() {
      return this.op;
    }

    public String getCmd() {
      return this.cmd;
    }
  }

  @Override
  public int compile(String cmd, boolean resetTaskIds) {
    LOG.info("cmd: " + cmd);
    long startTime = System.currentTimeMillis();
    PerfLogger perfLogger = PerfLogger.getPerfLogger();
    perfLogger.PerfLogBegin(IdgsDriver.class.getName(), PerfLogger.COMPILE);

    if (plan != null) {
      close();
      plan = null;
    }

    if (resetTaskIds) {
      TaskFactory.resetId();
    }
    
    // holder for parent command type/string when executing reentrant queries
    QueryState queryState = new QueryState();
    saveSession(queryState);

    try {
      String command = new VariableSubstitution().substitute(conf, cmd);
      context = new Context(conf);
      context.setTryCount(getTryCount());
      context.setCmd(command);

      ParseDriver parseDriver = new ParseDriver();
      ASTNode astTree = parseDriver.parse(command, context);
      astTree = ParseUtils.findRootNonNullToken(astTree);
      
      SessionState.get().initTxnMgr(conf);
      ValidTxnList txns = SessionState.get().getTxnMgr().getValidTxns();
      String txnStr = txns.toString();
      conf.set(ValidTxnList.VALID_TXNS_KEY, txnStr);

      BaseSemanticAnalyzer semantic = IdgsSemanticAnalyzerFactory.get(conf, astTree);
      @SuppressWarnings("unchecked")
      List<AbstractSemanticAnalyzerHook> hooks = (List<AbstractSemanticAnalyzerHook>) saHooksMethod.invoke(this, HiveConf.ConfVars.SEMANTIC_ANALYZER_HOOK, AbstractSemanticAnalyzerHook.class);

      // Do semantic analysis and plan generation
      if (hooks != null) {
        HiveSemanticAnalyzerHookContext hookContext = new HiveSemanticAnalyzerHookContextImpl();
        hookContext.setConf(conf);
        for (AbstractSemanticAnalyzerHook hook : hooks) {
          astTree = hook.preAnalyze(hookContext, astTree);
        }
        semantic.analyze(astTree, context);
        for (AbstractSemanticAnalyzerHook hook : hooks) {
          hook.postAnalyze(hookContext, semantic.getRootTasks());
        }
      } else {
        semantic.analyze(astTree, context);
      }

      LOG.info("Semantic Analysis Completed");

      // validate the plan
      semantic.validate();

      plan = new QueryPlan(command, semantic, perfLogger.getStartTime(PerfLogger.DRIVER_RUN));

      // Initialize FetchTask right here. Somehow Hive initializes it twice...
      if (semantic.getFetchTask() != null) {
        semantic.getFetchTask().initialize(conf, plan, null);
      }

      // get the output schema
      schema = Driver.getSchema(semantic, conf);

      // do the authorization check
      if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_AUTHORIZATION_ENABLED)) {
        try {
          perfLogger.PerfLogBegin(IdgsDriver.class.getName(), PerfLogger.DO_AUTHORIZATION);
          // Use reflection to invoke doAuthorization().
          doAuthMethod.invoke(this, semantic, cmd);
        } catch (AuthorizationException ex) {
          LOG.error("Authorization failed:" + ex.getMessage() + ". Use show grant to get more details.");
          return 403;
        } finally {
          perfLogger.PerfLogEnd(IdgsDriver.class.getName(), PerfLogger.DO_AUTHORIZATION);
        }
      }

      planField.set(this, plan);
      contextField.set(this, context);
      schemaField.set(this, schema);

      long endTime = System.currentTimeMillis();
      compileTime = endTime - startTime;
      return 0;
    } catch (SemanticException e) {
      errorMessage = "FAILED: Error in semantic analysis: " + e.getMessage();
      LOG.error(errorMessage + "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return (10);
    } catch (ParseException e) {
      errorMessage = "FAILED: Parse Error: " + e.getMessage();
      LOG.error(errorMessage + "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return (11);
    } catch (Exception e) {
      errorMessage = "FAILED: Hive Internal Error: " + Utilities.getNameMessage(e);
      LOG.error(errorMessage + "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return (12);
    } finally {
      perfLogger.PerfLogEnd(IdgsDriver.class.getName(), PerfLogger.COMPILE);
      restoreSession(queryState);
    }
  }

  public void saveSession(QueryState qs) {
    SessionState oldss = SessionState.get();
    if (oldss != null && oldss.getHiveOperation() != null) {
      qs.init(oldss.getHiveOperation(), oldss.getCmd());
    }
  }

  public void restoreSession(QueryState qs) {
    SessionState ss = SessionState.get();
    if (ss != null && qs != null && qs.isInitialized()) {
      ss.setCmd(qs.getCmd());
      ss.setCommandType(qs.getOp());
    }
  }
  
  @SuppressWarnings("unchecked")
  @Override
  public boolean getResults(@SuppressWarnings("rawtypes") List result) throws IOException, CommandNeedRetryException {
    int maxRows = 100;
    try {
      maxRows = (Integer) maxRowsField.get(this);
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
    
    ResultSet resultSet = getResultSet();
    if (resultSet == null) {
      return super.getResults(result);
    }
    
    List<RowData> results = resultSet.getResults(maxRows);
    if (results == null) {
      return false;
    }
    
    List<FieldSchema> fieldSchemas = getSchema().getFieldSchemas();
    for (RowData rowData : results) {
      Object[] row = new Object[fieldSchemas.size()];
      
      for (int i = 0; i < fieldSchemas.size(); ++ i) {
        FieldSchema schema = fieldSchemas.get(i);
        Object value = rowData.getFieldValue(schema.getName());
        row[i] = null;
        
        if (value != null) {
          String type = schema.getType();
          if (type.equalsIgnoreCase("smallint")) {
            if (value instanceof Integer) {
              row[i] = ((Integer) value).shortValue();
            }
            row[i] = Short.valueOf(value.toString());
          } else if (type.equalsIgnoreCase("tinyint")) {
            if (value instanceof Integer) {
              row[i] = ((Integer) value).byteValue();
            }
          } else if (type.equalsIgnoreCase("binary")) {
            if (value instanceof String) {
              row[i] = ((String) value).getBytes();
            }
          } else if (type.equalsIgnoreCase("timestamp")) {
            if (value instanceof String) {
              try {
                row[i] = Timestamp.valueOf((String) value);
              } catch (Exception e) {
                e.printStackTrace();
              }
            }
          } else if (type.equalsIgnoreCase("date")) {
            if (value instanceof String) {
              try {
                row[i] = Date.valueOf((String) value);
              } catch (Exception e) {
              }
            }
          } else {
            row[i] = value;
          }
        }
      }
      
      result.add(row);
    }
    
    return true;
  }
  
  @Override
  public boolean isFetchingTable() {
    ArrayList<Task<? extends Serializable>> rootTask = plan.getRootTasks();
    if (rootTask.size() != 1) {
      return false;
    }
    
    Task<? extends Serializable> task = rootTask.get(0);
    if (!(task instanceof IdgsTask)) {
      return false;
    }
    
    return true;
  }
  
  public ResultSet getResultSet() {
    ArrayList<Task<? extends Serializable>> rootTask = plan.getRootTasks();
    if (rootTask.size() != 1) {
      return null;
    }
    
    Task<? extends Serializable> task = rootTask.get(0);
    if (!(task instanceof IdgsTask)) {
      return null;
    }
    
    IdgsTask idgsTask = (IdgsTask) task;
    return idgsTask.getResultSet();
  }

  @Override
  public int execute() throws CommandNeedRetryException {
    long s = System.currentTimeMillis();
    int ret = super.execute();
    long e = System.currentTimeMillis();
    taskRunTime = e - s;
    
    return ret;
  }

  public long getTaskRunTime() {
    return taskRunTime;
  }

  public long getCompileTime() {
    return compileTime;
  }

}
