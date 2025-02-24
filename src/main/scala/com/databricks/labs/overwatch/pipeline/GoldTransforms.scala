package com.databricks.labs.overwatch.pipeline

import com.databricks.labs.overwatch.pipeline.TransformFunctions._
import com.databricks.labs.overwatch.utils.{BadConfigException, SparkSessionWrapper, TimeTypes}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Column, DataFrame}

trait GoldTransforms extends SparkSessionWrapper {

  import spark.implicits._

  protected def buildCluster()(df: DataFrame): DataFrame = {
    val clusterCols: Array[Column] = Array(
      'cluster_id,
      'actionName.alias("action"),
      'timestamp.alias("unixTimeMS"),
      from_unixtime('timestamp.cast("double") / 1000).cast("timestamp").alias("timestamp"),
      from_unixtime('timestamp.cast("double") / 1000).cast("timestamp").cast("date").alias("date"),
      'cluster_name,
      'driver_node_type_id.alias("driver_node_type"),
      'node_type_id.alias("node_type"),
      'num_workers,
      'autoscale,
      'autoTermination_minutes.alias("auto_termination_minutes"),
      'enable_elastic_disk,
      'is_automated,
      'cluster_type,
      'security_profile,
      'cluster_log_conf,
      'init_scripts,
      'custom_tags,
      'cluster_source,
      'spark_env_vars,
      'spark_conf,
      'acl_path_prefix,
      'driver_instance_pool_id,
      'instance_pool_id,
      'driver_instance_pool_name,
      'instance_pool_name,
      'spark_version,
      'idempotency_token,
      'organization_id,
      'deleted_by,
      'createdBy.alias("created_by"),
      'lastEditedBy.alias("last_edited_by")
    )
    df.select(clusterCols: _*)
  }

  protected def buildJobs()(df: DataFrame): DataFrame = {
    val jobCols: Array[Column] = Array(
      'organization_id,
      'jobId.alias("job_id"),
      'actionName.alias("action"),
      'timestamp.alias("unixTimeMS"),
      from_unixtime('timestamp.cast("double") / 1000).cast("timestamp").alias("timestamp"),
      from_unixtime('timestamp.cast("double") / 1000).cast("timestamp").cast("date").alias("date"),
      'jobName.alias("job_name"),
      'job_type,
      'timeout_seconds,
      'schedule,
      'notebook_path,
      'new_settings,
      struct(
        'existing_cluster_id,
        'new_cluster
      ).alias("cluster"),
      'aclPermissionSet,
      'grants,
      'targetUserId,
      'sessionId.alias("session_id"),
      'requestId.alias("request_id"),
      'userAgent.alias("user_agent"),
      'response,
      'sourceIPAddress.alias("source_ip_address"),
      'created_by,
      'created_ts,
      'deleted_by,
      'deleted_ts,
      'last_edited_by,
      'last_edited_ts
    )
    df.select(jobCols: _*)
  }

  protected def buildJobRuns()(df: DataFrame): DataFrame = {
    val jobRunCols: Array[Column] = Array(
      'runId.alias("run_id"),
      'run_name,
      $"JobRunTime.endEpochMS".alias("endEpochMS"),
      'jobRunTime.alias("job_runtime"),
      'jobId.alias("job_id"),
      'idInJob.alias("id_in_job"),
      'jobClusterType.alias("job_cluster_type"),
      'jobTaskType.alias("job_task_type"),
      'jobTerminalState.alias("job_terminal_state"),
      'jobTriggerType.alias("job_trigger_type"),
      'clusterId.alias("cluster_id"),
      'organization_id,
      'notebook_params,
      'libraries,
      'children,
      'workflow_context,
      'taskDetail.alias("task_detail"),
      'requestDetails.alias("request_detail"),
      'timeDetails.alias("time_detail")
    )
    df.select(jobRunCols: _*)
  }

  protected def buildNotebook()(df: DataFrame): DataFrame = {
    val notebookCols: Array[Column] = Array(
      'organization_id,
      'notebookId.alias("notebook_id"),
      'notebookName.alias("notebook_name"),
      'path.alias("notebook_path"),
      'clusterId.alias("cluster_id"),
      'actionName.alias("action"),
      'timestamp.alias("unixTimeMS"),
      from_unixtime('timestamp.cast("double") / 1000).cast("timestamp").alias("timestamp"),
      from_unixtime('timestamp.cast("double") / 1000).cast("timestamp").cast("date").alias("date"),
      'oldName.alias("old_name"),
      'oldPath.alias("old_path"),
      'newName.alias("new_name"),
      'newPath.alias("new_path"),
      'parentPath.alias("parent_path"),
      'userEmail.alias("user_email"),
      'requestId.alias("request_id"),
      'response
    )
    df.select(notebookCols: _*)
  }

  protected def buildAccountMod()(df: DataFrame): DataFrame = {
    df.select(
      'organization_id,
      'timestamp.alias("mod_unixTimeMS"),
      'date.alias("mod_date"),
      'actionName.alias("action"),
      'endpoint,
      'modified_by,
      'user_name,
      'user_id,
      'group_name,
      'group_id,
      'sourceIPAddress.alias("from_ip_address"),
      'userAgent.alias("user_agent"),
      'requestId.alias("request_id"),
      'response
    )
  }

  protected def buildLogin(accountModSilver: DataFrame)(df: DataFrame): DataFrame = {
    val userIdLookup = if (!accountModSilver.isEmpty) {
      accountModSilver.select('organization_id, 'user_name.alias("login_user"), 'user_id)
    } else Seq(("-99", "-99")).toDF("organization_id", "login_user")
    df.join(userIdLookup, Seq("organization_id", "login_user"), "left")
      .select(
        'organization_id,
        'timestamp.alias("login_unixTimeMS"),
        'login_date,
        'login_type,
        'login_user,
        'user_email,
        'ssh_login_details,
        'sourceIPAddress.alias("from_ip_address"),
        'userAgent.alias("user_agent"),
        'requestId.alias("request_id"),
        'response
      )
  }

  /**
   * The instanceDetails costing lookup table is often edited direclty by users. Thus, each time this module runs
   * the instanceDetails table is validated before continuing to guard against erroneous/missing cost data.
   * As of Overwatch v 0.4.2, the instanceDetails table is a type-2 table. The record with and activeUntil column
   * with a value of null will be considered the active record. There must be only one active record per key and
   * there must be no gaps between dates. ActiveUntil expires on 06-01-2021 for node type X then there must be
   * another record with activeUntil beginning on 06-01-2021 and a null activeUntil for this validation to pass.
   *
   * @param instanceDetails ETL table named instanceDetails referenced by the cloudDetail Target
   * @param snapDate        snapshot date of the pipeline "yyyy-MM-dd" format
   */
  private def validateInstanceDetails(
                                       instanceDetails: DataFrame,
                                       snapDate: String
                                     ): Unit = {
    val w = Window.partitionBy(lower(trim('API_name))).orderBy('activeFrom)
    val wKeyCheck = Window.partitionBy(lower(trim('API_name)), 'activeFrom, 'activeUntil).orderBy('activeFrom, 'activeUntil)
    val dfCheck = instanceDetails
      .withColumn("activeUntil", coalesce('activeUntil, lit(snapDate)))
      .withColumn("previousUntil", lag('activeUntil, 1).over(w))
      .withColumn("rnk", rank().over(wKeyCheck))
      .withColumn("rn", row_number().over(wKeyCheck))
      .withColumn("isValid", when('previousUntil.isNull, lit(true)).otherwise(
        'activeFrom === 'previousUntil
      ))
      .filter(!'isValid || 'rnk > 1 || 'rn > 1)

    if (!dfCheck.isEmpty) {
      val erroredRecordsReport = instanceDetails
        .withColumn("API_name", lower(trim('API_name)))
        .join(
          dfCheck
            .select(
              lower(trim('API_name)).alias("API_name"),
              'rnk, 'rn, 'previousUntil,
              datediff('activeFrom, 'previousUntil).alias("daysBetweenCurrentAndPrevious")
            ),
          Seq("API_name")
        )
        .orderBy('API_name, 'activeFrom, 'activeUntil)

      println("InstanceDetails Error Report: ")
      erroredRecordsReport.show(numRows = 1000, false)

      val badRecords = dfCheck.count()
      val badRawKeys = dfCheck.select('API_name).as[String].collect().mkString(", ")
      val errMsg = s"instanceDetails Invalid: Each key (API_name) must be unique for a given time period " +
        s"(activeFrom --> activeUntil) AND the previous costs activeUntil must run through the previous date " +
        s"such that the function 'datediff' returns 1. Please correct the instanceDetails table before continuing " +
        s"with this module.\nThe API_name keys with errors are: $badRawKeys. A total of $badRecords records were " +
        s"found in conflict."
      throw new BadConfigException(errMsg)
    }
  }

  private def getRunningSwitch(c: Column): Column = {
    when(c === "TERMINATING", lit(false))
      .when(c.isin("CREATING", "STARTING"), lit(true))
      .otherwise(lit(null).cast("boolean"))
  }

  /**
   * Temporary nastiness necessary to handle edge cases of states between runs. This will go away in 0.5.1
   * @param df
   * @param timeFilter
   * @return
   */
  private def getEventStates(df: DataFrame, timeFilter: Column): DataFrame = {
    val stateUnboundW = Window.partitionBy('organization_id, 'cluster_id).orderBy('timestamp)
    val stateUnboundReverse = Window.partitionBy('organization_id, 'cluster_id).orderBy('timestamp.desc)
    val stateFromCurrentW = Window.partitionBy('organization_id, 'cluster_id).rowsBetween(1L, 1000L).orderBy('timestamp)
    val stateUntilPreviousRowW = Window.partitionBy('organization_id, 'cluster_id).rowsBetween(Window.unboundedPreceding, -1L).orderBy('timestamp)
    val stateUntilCurrentW = Window.partitionBy('organization_id, 'cluster_id).rowsBetween(-1000L, -1L).orderBy('timestamp)
    val invalidEventChain = lead('runningSwitch, 1).over(stateUnboundW).isNotNull &&
      lead('runningSwitch, 1).over(stateUnboundW) === lead('previousSwitch, 1).over(stateUnboundW)

    // some states like EXPANDED_DISK and NODES_LOST, etc are excluded because they
    // occasionally do come after the cluster has been terminated; thus they are not a guaranteed event
    // goal is to be certain about the 99th percentile
    val runningStates = Array(
      "STARTING", "INIT_SCRIPTS_STARTED", "RUNNING", "CREATING",
      "RESIZING", "UPSIZE_COMPLETED", "DRIVER_HEALTHY"
    )

    df
      .selectExpr("*", "details.*")
      .drop("details")
      .filter(timeFilter)
      .withColumn("rnk", rank().over(stateUnboundReverse))
      .withColumn("rn", row_number().over(stateUnboundReverse))
      .withColumn( // if incremental df doesn't have a running switch get it from latest previous state
        "runningSwitch",
        coalesce(getRunningSwitch('type))//, getRunningSwitch('last_state_previous_run))
      )
      .withColumn(
        "previousSwitch",
        when('runningSwitch.isNotNull, last('runningSwitch, true).over(stateUntilPreviousRowW))
      )
      // pull values forward to populate final state
      // temp workaround until 0.5.1
      .withColumn(
        "invalidEventChainHandler",
        when(invalidEventChain, array(lit(false), lit(true))).otherwise(array(lit(false)))
      )
      .selectExpr("*", "explode(invalidEventChainHandler) as imputedTerminationEvent").drop("invalidEventChainHandler")
      .withColumn("lastRunningSwitch", last('runningSwitch, true).over(stateUntilCurrentW)) // previous on/off switch
      .withColumn("nextRunningSwitch", first('runningSwitch, true).over(stateFromCurrentW)) // next on/off switch
      .withColumn("type", when('imputedTerminationEvent, "TERMINATING").otherwise('type))
      .withColumn("timestamp", when('imputedTerminationEvent, lag('timestamp, 1).over(stateUnboundW) + 1L).otherwise('timestamp))
      // given no anomaly, set on/off state to previous state
      // if no previous state found, assume opposite of next state switch
      .withColumn("isRunning",coalesce(
        when('imputedTerminationEvent, lit(false)).otherwise('lastRunningSwitch),
        !'nextRunningSwitch
      ))
      // if isRunning still undetermined, use guaranteed events to create state anchors to identify isRunning anchors
      .withColumn("isRunning", when('isRunning.isNull && 'type.isin(runningStates: _*), lit(true)).otherwise('isRunning))
      // use the anchors to fill in the null gaps between the state changes to determine if running
      // if ultimately unable to be determined, assume not isRunning
      .withColumn("isRunning", coalesce(
        when('isRunning.isNull, last('isRunning, true).over(stateUntilCurrentW)).otherwise('isRunning),
        when('isRunning.isNull, !first('isRunning, true).over(stateFromCurrentW)).otherwise('isRunning),
        lit(false)
      )).drop("lastRunningSwitch", "nextRunningSwitch")
      .withColumn(
        "current_num_workers",
        when(!'isRunning || 'isRunning.isNull, lit(null).cast("long"))
          .otherwise(coalesce(
            'current_num_workers,
            $"cluster_size.num_workers",
            $"cluster_size.autoscale.min_workers",
            last(coalesce('current_num_workers, $"cluster_size.num_workers", $"cluster_size.autoscale.min_workers"), true)
              .over(stateUntilCurrentW)))
      )
      .withColumn(
        "target_num_workers",
        when(!'isRunning || 'isRunning.isNull, lit(null).cast("long"))
          .when('type === "CREATING", coalesce($"cluster_size.num_workers", $"cluster_size.autoscale.min_workers"))
          .otherwise(coalesce('target_num_workers, 'current_num_workers))
      )
  }

  protected def buildClusterStateFact(
                                       instanceDetails: DataFrame,
                                       clusterSnapshot: PipelineTable,
                                       clusterSpec: PipelineTable,
                                       untilTime: TimeTypes,
                                       fromTime: TimeTypes
                                     )(clusterEventsDF: DataFrame): DataFrame = {
    validateInstanceDetails(instanceDetails, untilTime.asDTString)

    val driverNodeDetails = instanceDetails
      .select('organization_id.alias("driver_orgid"), 'activeFrom, 'activeUntil,
        'API_Name.alias("driver_node_type_id_lookup"),
        struct(instanceDetails.columns map col: _*).alias("driverSpecs")
      )
      .withColumn("activeFromEpochMillis", unix_timestamp('activeFrom) * 1000)
      .withColumn("activeUntilEpochMillis",
        when('activeUntil.isNull, unix_timestamp(untilTime.asColumnTS) * 1000)
          .otherwise(unix_timestamp('activeUntil) * 1000)
      )

    val workerNodeDetails = instanceDetails
      .select('organization_id.alias("worker_orgid"), 'activeFrom, 'activeUntil,
        'API_Name.alias("node_type_id_lookup"),
        'automatedDBUPrice, 'interactiveDBUPrice, 'sqlComputeDBUPrice, 'jobsLightDBUPrice,
        struct(instanceDetails.columns map col: _*).alias("workerSpecs")
      )
      .withColumn("activeFromEpochMillis", unix_timestamp('activeFrom) * 1000)
      .withColumn("activeUntilEpochMillis",
        when('activeUntil.isNull, unix_timestamp(untilTime.asColumnTS) * 1000)
          .otherwise(unix_timestamp('activeUntil) * 1000)
      )

    val stateUnboundW = Window.partitionBy('organization_id, 'cluster_id).orderBy('timestamp)
    val stateUnboundReverse = Window.partitionBy('organization_id, 'cluster_id).orderBy('timestamp.desc)
    val uptimeW = Window.partitionBy('organization_id, 'cluster_id, 'reset_partition).orderBy('unixTimeMS_state_start)

    // get state before current run to init the running switch
    // Temporary fix until 0.5.1 when merges are enabled
    val clusterStateBeforeRun = getEventStates(clusterEventsDF, 'timestamp < fromTime.asUnixTimeMilli)
      .filter('rnk === 1) // get the latest state for each cluster
      .withColumn("types", collect_set('type).over(stateUnboundReverse))
      .withColumn("type",
        // sometimes two events such as UPSIZE_COMPLETED and TERMINATING come with same timestamp
        when(size('types) > 1 && array_contains('types, "TERMINATING"), lit("TERMINATING")) // when terminating state at same timestamp as other, capture terminating
          .when(size('types) > 1 && !array_contains('types, "TERMINATING"), first('type).over(stateUnboundReverse)) // when other states do not include terminating, grab the latest value as best as possible
          .otherwise('type) // when only single state, grab it
      )
      .filter('rn === 1) // get the final state prior to this run
      .filter('type =!= "TERMINATING") // if previous final state was terminating it was not removed from previous run so don't get it again
      .select(
        'organization_id, 'cluster_id, 'isRunning,
        'timestamp, 'type, 'current_num_workers, 'target_num_workers
      )

    val clusterEventsForCurrentRun = getEventStates(clusterEventsDF, 'timestamp >= fromTime.asUnixTimeMilli)
      .select(
        'organization_id, 'cluster_id, 'isRunning,
        'timestamp, 'type, 'current_num_workers, 'target_num_workers
      )

    val allClusterEventsInScope = clusterStateBeforeRun.unionByName(clusterEventsForCurrentRun)

    val nonBillableTypes = Array(
      "STARTING", "TERMINATING", "CREATING", "RESTARTING"
    )

    val nodeTypeLookup = clusterSpec.asDF
      .select('organization_id, 'cluster_id, 'cluster_name, 'custom_tags, 'timestamp, 'driver_node_type_id, 'node_type_id, 'spark_version)

    val nodeTypeLookup2 = clusterSnapshot.asDF
      .withColumn("timestamp", coalesce('terminated_time, 'start_time))
      .select('organization_id, 'cluster_id, 'cluster_name, to_json('custom_tags).alias("custom_tags"), 'timestamp, 'driver_node_type_id, 'node_type_id, 'spark_version)

    val clusterPotential = allClusterEventsInScope
      .toTSDF("timestamp", "organization_id", "cluster_id")
      .lookupWhen(
        nodeTypeLookup.toTSDF("timestamp", "organization_id", "cluster_id"),
        maxLookAhead = 1,
        tsPartitionVal = 16
      ).lookupWhen(
      nodeTypeLookup2.toTSDF("timestamp", "organization_id", "cluster_id"),
      maxLookAhead = 1,
      tsPartitionVal = 16
    ).df
      .withColumn("ts", from_unixtime('timestamp.cast("double") / lit(1000)).cast("timestamp"))
      .withColumn("date", 'ts.cast("date"))
      .withColumn("counter_reset",
        when(
          lag('type, 1).over(stateUnboundW).isin("TERMINATING", "RESTARTING", "EDITED") ||
            !'isRunning, lit(1)
        ).otherwise(lit(0))
      )
      .withColumn("reset_partition", sum('counter_reset).over(stateUnboundW))
      .withColumn("target_num_workers", last('target_num_workers, true).over(stateUnboundW))
      .withColumn("current_num_workers", last('current_num_workers, true).over(stateUnboundW))
      .withColumn("unixTimeMS_state_start", 'timestamp)
      .withColumn("unixTimeMS_state_end", (lead('timestamp, 1).over(stateUnboundW) - lit(1))) // subtract 1 millis
      .withColumn("timestamp_state_start", from_unixtime('unixTimeMS_state_start.cast("double") / lit(1000)).cast("timestamp"))
      .withColumn("timestamp_state_end", from_unixtime('unixTimeMS_state_end.cast("double") / lit(1000)).cast("timestamp")) // subtract 1.0 millis
      .filter('unixTimeMS_state_end.isNotNull && 'type =!= "TERMINATING") // don't pull the last state for the cluster unless it's terminating
      .withColumn("uptime_in_state_S", ('unixTimeMS_state_end - 'unixTimeMS_state_start) / lit(1000))
      .withColumn("uptime_since_restart_S",
        coalesce(
          when('counter_reset === 1, lit(0))
            .otherwise(sum('uptime_in_state_S).over(uptimeW)),
          lit(0)
        )
      )
      .withColumn("cloud_billable", 'isRunning)
      .withColumn("databricks_billable", 'isRunning && !'type.isin(nonBillableTypes: _*))
      .alias("clusterPotential")
      .join(
        driverNodeDetails.alias("driverNodeDetails"),
        $"clusterPotential.organization_id" === $"driverNodeDetails.driver_orgid" &&
          trim(lower($"clusterPotential.driver_node_type_id")) === trim(lower($"driverNodeDetails.driver_node_type_id_lookup")) &&
          $"clusterPotential.unixTimeMS_state_start"
            .between($"driverNodeDetails.activeFromEpochMillis", $"driverNodeDetails.activeUntilEpochMillis"),
        "left"
      )
      .drop("activeFrom", "activeUntil", "activeFromEpochMillis", "activeUntilEpochMillis", "driver_orgid", "driver_node_type_id_lookup")
      .alias("clusterPotential")
      .join(
        workerNodeDetails.alias("workerNodeDetails"),
        $"clusterPotential.organization_id" === $"workerNodeDetails.worker_orgid" &&
          trim(lower($"clusterPotential.node_type_id")) === trim(lower($"workerNodeDetails.node_type_id_lookup")) &&
          $"clusterPotential.unixTimeMS_state_start"
            .between($"workerNodeDetails.activeFromEpochMillis", $"workerNodeDetails.activeUntilEpochMillis"),
        "left")
      .drop("activeFrom", "activeUntil", "activeFromEpochMillis", "activeUntilEpochMillis", "worker_orgid", "node_type_id_lookup")
      .withColumn("worker_potential_core_S", when('databricks_billable, $"workerSpecs.vCPUs" * 'current_num_workers * 'uptime_in_state_S).otherwise(lit(0)))
      .withColumn("core_hours", when('isRunning,
        round(TransformFunctions.getNodeInfo("driver", "vCPUs", true) / lit(3600), 2) +
          round(TransformFunctions.getNodeInfo("worker", "vCPUs", true) / lit(3600), 2)
      ))
      .withColumn("uptime_in_state_H", 'uptime_in_state_S / lit(3600))
      .withColumn("isAutomated", isAutomated('cluster_name))
      // TODO -- add additional skus
      .withColumn("dbu_rate", when('isAutomated, 'automatedDBUPrice).otherwise('interactiveDBUPrice))
      .withColumn("days_in_state", size(sequence('timestamp_state_start.cast("date"), 'timestamp_state_end.cast("date"))))
      .withColumn("worker_potential_core_H", 'worker_potential_core_S / lit(3600))
      .withColumn("driver_compute_cost", Costs.compute('cloud_billable, $"driverSpecs.Compute_Contract_Price", lit(1), 'uptime_in_state_H))
      .withColumn("worker_compute_cost", Costs.compute('cloud_billable, $"workerSpecs.Compute_Contract_Price", 'target_num_workers, 'uptime_in_state_H))
      .withColumn("driver_dbu_cost", Costs.dbu('databricks_billable, $"driverSpecs.Hourly_DBUs", 'dbu_rate, lit(1), 'uptime_in_state_H))
      .withColumn("worker_dbu_cost", Costs.dbu('databricks_billable, $"workerSpecs.Hourly_DBUs", 'dbu_rate, 'current_num_workers, 'uptime_in_state_H))
      .withColumn("total_compute_cost", 'driver_compute_cost + 'worker_compute_cost)
      .withColumn("total_DBU_cost", 'driver_dbu_cost + 'worker_dbu_cost)
      .withColumn("total_driver_cost", 'driver_compute_cost + 'driver_dbu_cost)
      .withColumn("total_worker_cost", 'worker_compute_cost + 'worker_dbu_cost)
      .withColumn("total_cost", 'total_driver_cost + 'total_worker_cost)

    // TODO - finish this logic for pricing compute by sku for a final dbuRate
    //    val derivedDBURate = when('isAutomated && 'spark_version.like("apache-spark-%"), 'jobsLightDBUPrice)
    //      .when('isAutomated && !'spark_version.like("apache-spark-%"), 'automatedDBUPrice)


    val clusterStateFactCols: Array[Column] = Array(
      'organization_id,
      'cluster_id,
      'cluster_name,
      'custom_tags,
      'unixTimeMS_state_start,
      'unixTimeMS_state_end,
      'timestamp_state_start,
      'timestamp_state_end,
      'type.alias("state"),
      'driver_node_type_id,
      'node_type_id,
      'current_num_workers,
      'target_num_workers,
      'uptime_since_restart_S,
      'uptime_in_state_S,
      'uptime_in_state_H,
      'cloud_billable,
      'databricks_billable,
      'isAutomated,
      'dbu_rate,
      'days_in_state,
      'worker_potential_core_H,
      'core_hours,
      'driverSpecs,
      'workerSpecs,
      'driver_compute_cost,
      'worker_compute_cost,
      'driver_dbu_cost,
      'worker_dbu_cost,
      'total_compute_cost,
      'total_DBU_cost,
      'total_driver_cost,
      'total_worker_cost,
      'total_cost
    )

    clusterPotential
      .select(clusterStateFactCols: _*)
  }

  protected def buildJobRunCostPotentialFact(
                                              clusterStateFact: DataFrame,
                                              incrementalSparkJob: DataFrame,
                                              incrementalSparkTask: DataFrame
                                            )(newTerminatedJobRuns: DataFrame): DataFrame = {

    if (clusterStateFact.isEmpty) {
      spark.emptyDataFrame
    } else {

      val clusterPotentialWCosts = clusterStateFact
        .filter('unixTimeMS_state_start.isNotNull && 'unixTimeMS_state_end.isNotNull)

      val keyColNames = Array("organization_id", "cluster_id", "timestamp")
      val keys: Array[Column] = Array(keyColNames map col: _*)
      val lookupCols: Array[Column] = Array(
        'cluster_name, 'custom_tags, 'unixTimeMS_state_start, 'unixTimeMS_state_end, 'timestamp_state_start,
        'timestamp_state_end, 'state, 'cloud_billable, 'databricks_billable, 'uptime_in_state_H, 'current_num_workers, 'target_num_workers,
        //        lit(interactiveDBUPrice).alias("interactiveDBUPrice"), lit(automatedDBUPrice).alias("automatedDBUPrice"),
        $"driverSpecs.API_name".alias("driver_node_type_id"),
        $"driverSpecs.Compute_Contract_Price".alias("driver_compute_hourly"),
        $"driverSpecs.Hourly_DBUs".alias("driver_dbu_hourly"),
        $"workerSpecs.API_name".alias("node_type_id"),
        $"workerSpecs.Compute_Contract_Price".alias("worker_compute_hourly"),
        $"workerSpecs.Hourly_DBUs".alias("worker_dbu_hourly"),
        $"workerSpecs.vCPUs".alias("worker_cores"),
        'isAutomated,
        'dbu_rate,
        'worker_potential_core_H,
        'driver_compute_cost,
        'worker_compute_cost,
        'driver_dbu_cost,
        'worker_dbu_cost,
        'total_compute_cost,
        'total_DBU_cost,
        'total_driver_cost,
        'total_worker_cost,
        'total_cost
      )

      // GET POTENTIAL WITH COSTS
      val clusterPotentialInitialState = clusterPotentialWCosts
        .withColumn("timestamp", 'unixTimeMS_state_start)
        .select(keys ++ lookupCols: _*)

      val clusterPotentialIntermediateStates = clusterPotentialWCosts
        .select((keyColNames.filterNot(_ == "timestamp") map col) ++ lookupCols: _*)

      val clusterPotentialTerminalState = clusterPotentialWCosts
        .withColumn("timestamp", 'unixTimeMS_state_end)
        .select(keys ++ lookupCols: _*)

      // Adjust the uptimeInState to smooth the runtimes over the runPeriod across concurrent runs
      val stateLifecycleKeys = Seq("organization_id", "run_id", "cluster_id", "unixTimeMS_state_start")

      // for states (CREATING and STARTING) OR automated cluster runstate start is same as cluster state start
      // (i.e. not discounted to runStateStart)
      val runStateLastToStartStart = array_max(array('unixTimeMS_state_start, $"job_runtime.startEpochMS"))
      val runStateFirstToEnd = array_min(array('unixTimeMS_state_end, $"job_runtime.endEpochMS"))

      val jobRunInitialState = newTerminatedJobRuns //jobRun_gold
        .withColumn("timestamp", $"job_runtime.startEpochMS")
        .toTSDF("timestamp", "organization_id", "cluster_id")
        .lookupWhen(
          clusterPotentialInitialState
            .toTSDF("timestamp", "organization_id", "cluster_id"),
          tsPartitionVal = 16, maxLookAhead = 1L
        ).df
        .drop("timestamp")
        .filter('unixTimeMS_state_start.isNotNull && 'unixTimeMS_state_end.isNotNull)
        .withColumn("runtime_in_cluster_state",
          when('state.isin("CREATING", "STARTING") || 'job_cluster_type === "new", 'uptime_in_state_H * 1000 * 3600) // get true cluster time when state is guaranteed fully initial
            .otherwise(runStateFirstToEnd - $"job_runtime.startEpochMS")) // otherwise use jobStart as beginning time and min of stateEnd or jobEnd for end time )
        .withColumn("lifecycleState", lit("init"))

      val jobRunTerminalState = newTerminatedJobRuns
        .withColumn("timestamp", $"job_runtime.endEpochMS")
        .toTSDF("timestamp", "organization_id", "cluster_id")
        .lookupWhen(
          clusterPotentialTerminalState
            .toTSDF("timestamp", "organization_id", "cluster_id"),
          tsPartitionVal = 8, maxLookback = 0L, maxLookAhead = 1L
        ).df
        .drop("timestamp")
        .filter('unixTimeMS_state_start.isNotNull && 'unixTimeMS_state_end.isNotNull && 'unixTimeMS_state_end > $"job_runtime.endEpochMS")
        .join(jobRunInitialState.select(stateLifecycleKeys map col: _*), stateLifecycleKeys, "leftanti") // filter out beginning states
        .withColumn("runtime_in_cluster_state", $"job_runtime.endEpochMS" - runStateLastToStartStart)
        .withColumn("lifecycleState", lit("terminal"))

      val topClusters = newTerminatedJobRuns
        .filter('organization_id.isNotNull && 'cluster_id.isNotNull)
        .groupBy('organization_id, 'cluster_id).count
        .orderBy('count.desc).limit(40)
        .select(array('organization_id, 'cluster_id)).as[Seq[String]].collect.toSeq

      val jobRunIntermediateStates = newTerminatedJobRuns.alias("jr")
        .join(clusterPotentialIntermediateStates.alias("cpot").hint("SKEW", Seq("organization_id", "cluster_id"), topClusters),
          $"jr.organization_id" === $"cpot.organization_id" &&
            $"jr.cluster_id" === $"cpot.cluster_id" &&
            $"cpot.unixTimeMS_state_start" > $"jr.job_runtime.startEpochMS" && // only states beginning after job start and ending before
            $"cpot.unixTimeMS_state_end" < $"jr.job_runtime.endEpochMS"
        )
        .drop($"cpot.cluster_id").drop($"cpot.organization_id")
        .join(jobRunInitialState.select(stateLifecycleKeys map col: _*), stateLifecycleKeys, "leftanti") // filter out beginning states
        .join(jobRunTerminalState.select(stateLifecycleKeys map col: _*), stateLifecycleKeys, "leftanti") // filter out ending states
        .withColumn("runtime_in_cluster_state", 'unixTimeMS_state_end - 'unixTimeMS_state_start)
        .withColumn("lifecycleState", lit("intermediate"))


      val jobRunByClusterState = jobRunInitialState
        .unionByName(jobRunIntermediateStates)
        .unionByName(jobRunTerminalState)

      // Derive runStateConcurrency to derive runState fair share or utilization
      // runStateUtilization = runtimeInRunState / sum(overlappingRuntimesInState)

      val runstateKeys = $"obs.organization_id" === $"lookup.organization_id" &&
        $"obs.cluster_id" === $"lookup.cluster_id" &&
        $"obs.unixTimeMS_state_start" === $"lookup.unixTimeMS_state_start" &&
        $"obs.unixTimeMS_state_end" === $"lookup.unixTimeMS_state_end"

      val startsBefore = $"lookup.run_state_start_epochMS" < $"obs.run_state_start_epochMS"
      val startsDuring = $"lookup.run_state_start_epochMS" > $"obs.run_state_start_epochMS" && $"lookup.run_state_start_epochMS" < $"obs.run_state_end_epochMS" // exclusive
      val endsDuring = $"lookup.run_state_end_epochMS" > $"obs.run_state_start_epochMS" && $"lookup.run_state_end_epochMS" < $"obs.run_state_end_epochMS" // exclusive
      val endsAfter = $"lookup.run_state_end_epochMS" > $"obs.run_state_end_epochMS"
      val startsEndsWithin = $"lookup.run_state_start_epochMS".between($"obs.run_state_start_epochMS", $"obs.run_state_end_epochMS") &&
        $"lookup.run_state_end_epochMS".between($"obs.run_state_start_epochMS", $"obs.run_state_end_epochMS") // inclusive

      val simplifiedJobRunByClusterState = jobRunByClusterState
        .filter('job_cluster_type === "existing") // only relevant for interactive clusters
        .withColumn("run_state_start_epochMS", runStateLastToStartStart)
        .withColumn("run_state_end_epochMS", runStateFirstToEnd)
        .select(
          'organization_id, 'run_id, 'cluster_id, 'run_state_start_epochMS, 'run_state_end_epochMS, 'unixTimeMS_state_start, 'unixTimeMS_state_end
        )

      // sum of run_state_times starting before ending during
      val runStateBeforeEndsDuring = simplifiedJobRunByClusterState.alias("obs")
        .join(simplifiedJobRunByClusterState.alias("lookup"), runstateKeys && startsBefore && endsDuring)
        .withColumn("relative_runtime_in_runstate", $"lookup.run_state_end_epochMS" - $"obs.unixTimeMS_state_start") // runStateEnd minus clusterStateStart
        .select(
          $"obs.organization_id", $"obs.run_id", $"obs.cluster_id", $"obs.run_state_start_epochMS", $"obs.run_state_end_epochMS", $"obs.unixTimeMS_state_start", $"obs.unixTimeMS_state_end", 'relative_runtime_in_runstate
        )

      // sum of run_state_times starting during ending after
      val runStateAfterBeginsDuring = simplifiedJobRunByClusterState.alias("obs")
        .join(simplifiedJobRunByClusterState.alias("lookup"), runstateKeys && startsDuring && endsAfter)
        .withColumn("relative_runtime_in_runstate", $"lookup.unixTimeMS_state_end" - $"obs.run_state_start_epochMS") // clusterStateEnd minus runStateStart
        .select(
          $"obs.organization_id", $"obs.run_id", $"obs.cluster_id", $"obs.run_state_start_epochMS", $"obs.run_state_end_epochMS", $"obs.unixTimeMS_state_start", $"obs.unixTimeMS_state_end", 'relative_runtime_in_runstate
        )

      // sum of run_state_times starting and ending during
      val runStateBeginEndDuring = simplifiedJobRunByClusterState.alias("obs")
        .join(simplifiedJobRunByClusterState.alias("lookup"), runstateKeys && startsEndsWithin)
        .withColumn("relative_runtime_in_runstate", $"lookup.run_state_end_epochMS" - $"obs.run_state_start_epochMS") // runStateEnd minus runStateStart
        .select(
          $"obs.organization_id", $"obs.run_id", $"obs.cluster_id", $"obs.run_state_start_epochMS", $"obs.run_state_end_epochMS", $"obs.unixTimeMS_state_start", $"obs.unixTimeMS_state_end", 'relative_runtime_in_runstate
        )

      val cumulativeRunStateRunTimeByRunState = runStateBeforeEndsDuring
        .unionByName(runStateAfterBeginsDuring)
        .unionByName(runStateBeginEndDuring)
        .groupBy('organization_id, 'run_id, 'cluster_id, 'unixTimeMS_state_start, 'unixTimeMS_state_end) // runstate
        .agg(
          sum('relative_runtime_in_runstate).alias("cum_runtime_in_cluster_state"), // runtime in clusterState
          (sum(lit(1)) - lit(1)).alias("overlapping_run_states") // subtract one for self run
        )
        .repartition()
        .cache

      val runStateWithUtilizationAndCosts = jobRunByClusterState
        .join(cumulativeRunStateRunTimeByRunState, Seq("organization_id", "run_id", "cluster_id", "unixTimeMS_state_start", "unixTimeMS_state_end"), "left")
        .withColumn("cluster_type", when('job_cluster_type === "new", lit("automated")).otherwise(lit("interactive")))
        //        .withColumn("dbu_rate", when('cluster_type === "automated", 'automatedDBUPrice).otherwise('interactiveDBUPrice)) // removed 4.2
        .withColumn("state_utilization_percent", 'runtime_in_cluster_state / 1000 / 3600 / 'uptime_in_state_H) // run runtime as percent of total state time
        .withColumn("run_state_utilization",
          when('cluster_type === "interactive", least('runtime_in_cluster_state / 'cum_runtime_in_cluster_state, lit(1.0)))
            .otherwise(lit(1.0))
        ) // determine share of cluster when interactive as runtime / all overlapping run runtimes
        .withColumn("overlapping_run_states", when('cluster_type === "interactive", 'overlapping_run_states).otherwise(lit(0)))
        //        .withColumn("overlapping_run_states", when('cluster_type === "automated", lit(0)).otherwise('overlapping_run_states)) // removed 4.2
        .withColumn("running_days", sequence($"job_runtime.startTS".cast("date"), $"job_runtime.endTS".cast("date")))
        .withColumn("driver_compute_cost", 'driver_compute_cost * 'state_utilization_percent * 'run_state_utilization)
        .withColumn("driver_dbu_cost", 'driver_dbu_cost * 'state_utilization_percent * 'run_state_utilization)
        .withColumn("worker_compute_cost", 'worker_compute_cost * 'state_utilization_percent * 'run_state_utilization)
        .withColumn("worker_dbu_cost", 'worker_dbu_cost * 'state_utilization_percent * 'run_state_utilization)
        .withColumn("total_driver_cost", 'driver_compute_cost + 'driver_dbu_cost)
        .withColumn("total_worker_cost", 'worker_compute_cost + 'worker_dbu_cost)
        .withColumn("total_compute_cost", 'driver_compute_cost + 'worker_compute_cost)
        .withColumn("total_dbu_cost", 'driver_dbu_cost + 'worker_dbu_cost)
        .withColumn("total_cost", 'total_driver_cost + 'total_worker_cost)

      val jobRunCostPotential = runStateWithUtilizationAndCosts
        .groupBy(
          'organization_id,
          'job_id,
          'id_in_job,
          'endEpochMS,
          'job_runtime,
          'job_terminal_state.alias("run_terminal_state"),
          'job_trigger_type.alias("run_trigger_type"),
          'job_task_type.alias("run_task_type"),
          'cluster_id,
          'cluster_name,
          'cluster_type,
          'custom_tags,
          'driver_node_type_id,
          'node_type_id,
          'dbu_rate
        )
        .agg(
          first('running_days).alias("running_days"),
          greatest(round(avg('run_state_utilization), 4), lit(0.0)).alias("avg_cluster_share"),
          greatest(round(avg('overlapping_run_states), 2), lit(0.0)).alias("avg_overlapping_runs"),
          greatest(max('overlapping_run_states), lit(0.0)).alias("max_overlapping_runs"),
          sum(lit(1)).alias("run_cluster_states"),
          greatest(round(sum('worker_potential_core_H), 6), lit(0)).alias("worker_potential_core_H"),
          greatest(round(sum('driver_compute_cost), 6), lit(0)).alias("driver_compute_cost"),
          greatest(round(sum('driver_dbu_cost), 6), lit(0)).alias("driver_dbu_cost"),
          greatest(round(sum('worker_compute_cost), 6), lit(0)).alias("worker_compute_cost"),
          greatest(round(sum('worker_dbu_cost), 6), lit(0)).alias("worker_dbu_cost"),
          greatest(round(sum('total_driver_cost), 6), lit(0)).alias("total_driver_cost"),
          greatest(round(sum('total_worker_cost), 6), lit(0)).alias("total_worker_cost"),
          greatest(round(sum('total_compute_cost), 6), lit(0)).alias("total_compute_cost"),
          greatest(round(sum('total_dbu_cost), 6), lit(0)).alias("total_dbu_cost"),
          greatest(round(sum('total_cost), 6), lit(0)).alias("total_cost")
        )

      // GET UTILIZATION BY KEY
      // IF incremental spark events are present calculate utilization, otherwise just return with NULLS
      // Spark events are commonly missing if no clusters are logging and/or in test environments
      if (!incrementalSparkJob.isEmpty) {
        val sparkJobMini = incrementalSparkJob
          .select('organization_id, 'date, 'spark_context_id, 'job_group_id,
            'job_id, explode('stage_ids).alias("stage_id"), 'db_job_id, 'db_id_in_job)
          .filter('db_job_id.isNotNull && 'db_id_in_job.isNotNull)

        val sparkTaskMini = incrementalSparkTask
          .select('organization_id, 'date, 'spark_context_id, 'stage_id,
            'stage_attempt_id, 'task_id, 'task_attempt_id,
            $"task_runtime.runTimeMS", $"task_runtime.endTS".cast("date").alias("spark_task_termination_date"))

        val jobRunUtilRaw = sparkJobMini.alias("sparkJobMini")
          .joinWithLag(
            sparkTaskMini,
            Seq("organization_id", "date", "spark_context_id", "stage_id"),
            "date"
          )
          .withColumn("spark_task_runtime_H", 'runtimeMS / lit(1000) / lit(3600))
          .withColumnRenamed("job_id", "spark_job_id")
          .withColumnRenamed("stage_id", "spark_stage_id")
          .withColumnRenamed("task_id", "spark_task_id")

        val jobRunSparkUtil = jobRunUtilRaw
          .groupBy('organization_id, 'db_job_id, 'db_id_in_job)
          .agg(
            sum('runTimeMS).alias("spark_task_runtimeMS"),
            round(sum('spark_task_runtime_H), 4).alias("spark_task_runtime_H")
          )

        jobRunCostPotential.alias("jrCostPot")
          .join(
            jobRunSparkUtil.withColumnRenamed("organization_id", "orgId").alias("jrSparkUtil"),
            $"jrCostPot.organization_id" === $"jrSparkUtil.orgId" &&
              $"jrCostPot.job_id" === $"jrSparkUtil.db_job_id" &&
              $"jrCostPot.id_in_job" === $"jrSparkUtil.db_id_in_job",
            "left"
          )
          .drop("db_job_id", "db_id_in_job", "orgId")
          .withColumn("job_run_cluster_util", round(('spark_task_runtime_H / 'worker_potential_core_H), 4))
      } else {
        jobRunCostPotential
          .withColumn("spark_task_runtimeMS", lit(null).cast("long"))
          .withColumn("spark_task_runtime_H", lit(null).cast("double"))
          .withColumn("job_run_cluster_util", lit(null).cast("double"))
      }

    }


  }

  protected def buildSparkJob(
                               cloudProvider: String
                             )(df: DataFrame): DataFrame = {

    val jobGroupW = Window.partitionBy('organization_id, 'SparkContextID, $"PowerProperties.JobGroupID")
    val executionW = Window.partitionBy('organization_id, 'SparkContextID, $"PowerProperties.ExecutionID")
    val principalObjectIDW = Window.partitionBy($"PowerProperties.principalIdpObjectId")
    val isolationIDW = Window.partitionBy('organization_id, 'SparkContextID, $"PowerProperties.SparkDBIsolationID")
    val replIDW = Window.partitionBy('organization_id, 'SparkContextID, $"PowerProperties.SparkDBREPLID")
      .orderBy('startTimestamp).rowsBetween(Window.unboundedPreceding, Window.currentRow)
    val notebookW = Window.partitionBy('organization_id, 'SparkContextID, $"PowerProperties.NotebookID")
      .orderBy('startTimestamp).rowsBetween(Window.unboundedPreceding, Window.currentRow)

    val cloudSpecificUserImputations = if (cloudProvider == "azure") {
      df.withColumn("user_email", $"PowerProperties.UserEmail")
        .withColumn("user_email",
          when('user_email.isNull,
            first('user_email, ignoreNulls = true).over(principalObjectIDW)).otherwise('user_email))
    } else {
      df.withColumn("user_email", $"PowerProperties.UserEmail")
    }

    val sparkJobsWImputedUser = cloudSpecificUserImputations
      .withColumn("user_email",
        when('user_email.isNull,
          first('user_email, ignoreNulls = true).over(isolationIDW)).otherwise('user_email))
      .withColumn("user_email",
        when('user_email.isNull,
          first('user_email, ignoreNulls = true).over(jobGroupW)).otherwise('user_email))
      .withColumn("user_email",
        when('user_email.isNull,
          first('user_email, ignoreNulls = true).over(executionW)).otherwise('user_email))
      .withColumn("user_email",
        when('user_email.isNull,
          last('user_email, ignoreNulls = true).over(replIDW)).otherwise('user_email))
      .withColumn("user_email",
        when('user_email.isNull,
          last('user_email, ignoreNulls = true).over(notebookW)).otherwise('user_email))

    val sparkJobCols: Array[Column] = Array(
      'organization_id,
      'SparkContextID.alias("spark_context_id"),
      'JobID.alias("job_id"),
      'JobGroupID.alias("job_group_id"),
      'ExecutionID.alias("execution_id"),
      'StageIDs.alias("stage_ids"),
      $"PowerProperties.ClusterDetails.ClusterID".alias("cluster_id"),
      $"PowerProperties.NotebookID".alias("notebook_id"),
      $"PowerProperties.NotebookPath".alias("notebook_path"),
      $"PowerProperties.SparkDBJobID".alias("db_job_id"),
      $"PowerProperties.SparkDBRunID".alias("db_run_id"),
      $"PowerProperties.sparkDBJobType".alias("db_job_type"),
      'startTimestamp.alias("unixTimeMS"),
      from_unixtime('startTimestamp.cast("double") / 1000).cast("timestamp").alias("timestamp"),
      from_unixtime('startTimestamp.cast("double") / 1000).cast("timestamp").cast("date").alias("date"),
      'JobRunTime.alias("job_runtime"),
      'JobResult.alias("job_result"),
      'startFilenameGroup.alias("event_log_start"),
      'endFilenameGroup.alias("event_log_end"),
      'user_email
    )

    val sparkContextW = Window.partitionBy('organization_id, 'spark_context_id)

    val isDatabricksJob = 'job_group_id.like("%job-%-run-%")

    sparkJobsWImputedUser
      .select(sparkJobCols: _*)
      .withColumn("cluster_id", first('cluster_id, ignoreNulls = true).over(sparkContextW))
      .withColumn("jobGroupAr", split('job_group_id, "_")(2))
      .withColumn("db_job_id",
        when(isDatabricksJob && 'db_job_id.isNull,
          split(regexp_extract('jobGroupAr, "(job-\\d+)", 1), "-")(1))
          .otherwise('db_job_id)
      )
      .withColumn("db_id_in_job",
        when(isDatabricksJob && 'db_run_id.isNull,
          split(regexp_extract('jobGroupAr, "(-run-\\d+)", 1), "-")(2))
          .otherwise('db_run_id)
      )
  }

  protected def buildSparkStage()(df: DataFrame): DataFrame = {
    val sparkStageCols: Array[Column] = Array(
      'organization_id,
      'SparkContextID.alias("spark_context_id"),
      'StageID.alias("stage_id"),
      'StageAttemptID.alias("stage_attempt_id"),
      'clusterId.alias("cluster_id"),
      'startTimestamp.alias("unixTimeMS"),
      from_unixtime('startTimestamp.cast("double") / 1000).cast("timestamp").alias("timestamp"),
      from_unixtime('startTimestamp.cast("double") / 1000).cast("timestamp").cast("date").alias("date"),
      'StageRunTime.alias("stage_runtime"),
      'StageInfo.alias("stage_info"),
      'startFilenameGroup.alias("event_log_start"),
      'endFilenameGroup.alias("event_log_end")
    )
    df.select(sparkStageCols: _*)
  }

  protected def buildSparkTask()(df: DataFrame): DataFrame = {
    val sparkTaskCols: Array[Column] = Array(
      'organization_id,
      'SparkContextID.alias("spark_context_id"),
      'TaskID.alias("task_id"),
      'TaskAttempt.alias("task_attempt_id"),
      'StageID.alias("stage_id"),
      'StageAttemptID.alias("stage_attempt_id"),
      'clusterId.alias("cluster_id"),
      'ExecutorID.alias("executor_id"),
      'Host.alias("host"),
      'startTimestamp.alias("unixTimeMS"),
      from_unixtime('startTimestamp.cast("double") / 1000).cast("timestamp").alias("timestamp"),
      from_unixtime('startTimestamp.cast("double") / 1000).cast("timestamp").cast("date").alias("date"),
      'TaskRunTime.alias("task_runtime"),
      'TaskMetrics.alias("task_metrics"),
      'TaskInfo.alias("task_info"),
      'TaskType.alias("task_type"),
      'TaskEndReason.alias("task_end_reason"),
      'startFilenameGroup.alias("event_log_start"),
      'endFilenameGroup.alias("event_log_end")
    )
    df.select(sparkTaskCols: _*)
  }

  protected def buildSparkExecution()(df: DataFrame): DataFrame = {
    val sparkExecutionCols: Array[Column] = Array(
      'organization_id,
      'SparkContextID.alias("spark_context_id"),
      'ExecutionID.alias("execution_id"),
      'clusterId.alias("cluster_id"),
      'description,
      'details,
      'startTimestamp.alias("unixTimeMS"),
      from_unixtime('startTimestamp.cast("double") / 1000).cast("timestamp").alias("timestamp"),
      from_unixtime('startTimestamp.cast("double") / 1000).cast("timestamp").cast("date").alias("date"),
      'SqlExecutionRunTime.alias("sql_execution_runtime"),
      'startFilenameGroup.alias("event_log_start"),
      'endFilenameGroup.alias("event_log_end")
    )
    df.select(sparkExecutionCols: _*)
  }

  protected def buildSparkExecutor()(df: DataFrame): DataFrame = {
    val sparkExecutorCols: Array[Column] = Array(
      'organization_id,
      'SparkContextID.alias("spark_context_id"),
      'ExecutorID.alias("executor_id"),
      'clusterId.alias("cluster_id"),
      'ExecutorInfo.alias("executor_info"),
      'RemovedReason.alias("removed_reason"),
      'ExecutorAliveTime.alias("executor_alivetime"),
      'addedTimestamp.alias("unixTimeMS"),
      from_unixtime('addedTimestamp.cast("double") / 1000).cast("timestamp").alias("timestamp"),
      from_unixtime('addedTimestamp.cast("double") / 1000).cast("timestamp").cast("date").alias("date"),
      'startFilenameGroup.alias("event_log_start"),
      'endFilenameGroup.alias("event_log_end")
    )
    df.select(sparkExecutorCols: _*)
  }

  protected val clusterViewColumnMapping: String =
    """
      |organization_id, cluster_id, action, unixTimeMS, timestamp, date, cluster_name, driver_node_type, node_type, num_workers,
      |autoscale, auto_termination_minutes, enable_elastic_disk, is_automated, cluster_type, security_profile, cluster_log_conf,
      |init_scripts, custom_tags, cluster_source, spark_env_vars, spark_conf, acl_path_prefix,
      |driver_instance_pool_id, instance_pool_id, driver_instance_pool_name, instance_pool_name,
      |spark_version, idempotency_token, deleted_by, created_by, last_edited_by
      |""".stripMargin

  protected val jobViewColumnMapping: String =
    """
      |organization_id, job_id, action, unixTimeMS, timestamp, date, job_name, job_type, timeout_seconds, schedule,
      |notebook_path, new_settings, cluster, aclPermissionSet, grants, targetUserId, session_id, request_id, user_agent,
      |response, source_ip_address, created_by, created_ts, deleted_by, deleted_ts, last_edited_by, last_edited_ts
      |""".stripMargin

  protected val jobRunViewColumnMapping: String =
    """
      |organization_id, run_id, run_name, job_runtime, job_id, id_in_job, job_cluster_type, job_task_type,
      |job_terminal_state, job_trigger_type, cluster_id, notebook_params, libraries, children, workflow_context,
      |task_detail, request_detail, time_detail
      |""".stripMargin

  protected val jobRunCostPotentialFactViewColumnMapping: String =
    """
      |organization_id, job_id, id_in_job, job_runtime, run_terminal_state, run_trigger_type, run_task_type, cluster_id,
      |cluster_name, cluster_type, custom_tags, driver_node_type_id, node_type_id, dbu_rate, running_days,
      |run_cluster_states, avg_cluster_share, avg_overlapping_runs, max_overlapping_runs, worker_potential_core_H,
      |driver_compute_cost, driver_dbu_cost, worker_compute_cost, worker_dbu_cost, total_driver_cost, total_worker_cost,
      |total_compute_cost, total_dbu_cost, total_cost, spark_task_runtimeMS, spark_task_runtime_H, job_run_cluster_util
      |""".stripMargin

  protected val notebookViewColumnMappings: String =
    """
      |organization_id, notebook_id, notebook_name, notebook_path, cluster_id, action, unixTimeMS, timestamp, date, old_name, old_path,
      |new_name, new_path, parent_path, user_email, request_id, response
      |""".stripMargin

  protected val accountModViewColumnMappings: String =
    """
      |organization_id, mod_unixTimeMS, mod_date, action, endpoint, modified_by, user_name, user_id,
      |group_name, group_id, from_ip_address, user_agent, request_id, response
      |""".stripMargin

  protected val accountLoginViewColumnMappings: String =
    """
      |organization_id, login_unixTimeMS, login_date, login_type, login_user, user_email, ssh_login_details
      |from_ip_address, user_agent, request_id, response
      |""".stripMargin

  protected val clusterStateFactViewColumnMappings: String =
    """
      |organization_id, cluster_id, cluster_name, custom_tags, unixTimeMS_state_start, unixTimeMS_state_end,
      |timestamp_state_start, timestamp_state_end, state, driver_node_type_id, node_type_id, current_num_workers,
      |target_num_workers, uptime_since_restart_S, uptime_in_state_S, uptime_in_state_H, cloud_billable,
      |databricks_billable, isAutomated, dbu_rate, days_in_state, worker_potential_core_H, core_hours, driver_compute_cost,
      |worker_compute_cost, driver_dbu_cost, worker_dbu_cost, total_compute_cost, total_DBU_cost,
      |total_driver_cost, total_worker_cost, total_cost, driverSpecs, workerSpecs
      |""".stripMargin

  protected val sparkJobViewColumnMapping: String =
    """
      |organization_id, spark_context_id, job_id, job_group_id, execution_id, stage_ids, cluster_id, notebook_id, notebook_path,
      |db_job_id, db_id_in_job, db_job_type, unixTimeMS, timestamp, date, job_runtime, job_result, event_log_start,
      |event_log_end, user_email
      |""".stripMargin

  protected val sparkStageViewColumnMapping: String =
    """
      |organization_id, spark_context_id, stage_id, stage_attempt_id, cluster_id, unixTimeMS, timestamp, date, stage_runtime,
      |stage_info, event_log_start, event_log_end
      |""".stripMargin

  protected val sparkTaskViewColumnMapping: String =
    """
      |organization_id, spark_context_id, task_id, task_attempt_id, stage_id, stage_attempt_id, cluster_id, executor_id, host,
      |unixTimeMS, timestamp, date, task_runtime, task_metrics, task_info, task_type, task_end_reason,
      |event_log_start, event_log_end
      |""".stripMargin

  protected val sparkExecutionViewColumnMapping: String =
    """
      |organization_id, spark_context_id, execution_id, cluster_id, description, details, unixTimeMS, timestamp, date,
      |sql_execution_runtime, event_log_start, event_log_end
      |""".stripMargin

  protected val sparkExecutorViewColumnMapping: String =
    """
      |organization_id, spark_context_id, executor_id, cluster_id, executor_info, removed_reason, executor_alivetime,
      |unixTimeMS, timestamp, date, event_log_start, event_log_end
      |""".stripMargin

}
