package com.taotao.dynamic.service;

import com.dangdang.ddframe.job.config.JobCoreConfiguration;
import com.dangdang.ddframe.job.config.JobTypeConfiguration;
import com.dangdang.ddframe.job.config.dataflow.DataflowJobConfiguration;
import com.dangdang.ddframe.job.config.script.ScriptJobConfiguration;
import com.dangdang.ddframe.job.config.simple.SimpleJobConfiguration;
import com.dangdang.ddframe.job.event.rdb.JobEventRdbConfiguration;
import com.dangdang.ddframe.job.executor.handler.JobProperties;
import com.dangdang.ddframe.job.lite.config.LiteJobConfiguration;
import com.dangdang.ddframe.job.lite.spring.api.SpringJobScheduler;
import com.dangdang.ddframe.job.reg.zookeeper.ZookeeperRegistryCenter;
import com.taotao.dynamic.bean.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * @Description:
 * @Auther: zhangtao
 * @Date: 2022/04/16/17:36
 */
@Service
public class JobService {

    private Logger logger = LoggerFactory.getLogger(JobService.class);
    @Autowired
    private ZookeeperRegistryCenter zookeeperRegistryCenter;
    @Autowired
    private ApplicationContext ctx;


    public void addJob(Job job) {
        JobCoreConfiguration coreConfig = JobCoreConfiguration.newBuilder(job.getJobName(), job.getCron(), job.getShardingTotalCount()).shardingItemParameters(job.getShardingItemParameters()).description(job.getDescription()).failover(job.isFailover()).jobParameter(job.getJobParameter()).misfire(job.isMisfire()).jobProperties(JobProperties.JobPropertiesEnum.JOB_EXCEPTION_HANDLER.getKey(), job.getJobProperties().getJobExceptionHandler()).jobProperties(JobProperties.JobPropertiesEnum.EXECUTOR_SERVICE_HANDLER.getKey(), job.getJobProperties().getExecutorServiceHandler()).build();
        LiteJobConfiguration jobConfig = null;
        JobTypeConfiguration typeConfig = null;
        String jobType = job.getJobType();

        //	我到底要创建什么样的任务
        if (jobType.equals("SIMPLE")) {
            typeConfig = new SimpleJobConfiguration(coreConfig, job.getJobClass());
        }

        if (jobType.equals("DATAFLOW")) {
            typeConfig = new DataflowJobConfiguration(coreConfig, job.getJobClass(), job.isStreamingProcess());
        }

        if (jobType.equals("SCRIPT")) {
            typeConfig = new ScriptJobConfiguration(coreConfig, job.getScriptCommandLine());
        }
        //构建LiteJobConfiguration
        jobConfig = LiteJobConfiguration.newBuilder((JobTypeConfiguration) typeConfig).overwrite(job.isOverwrite()).disabled(job.isDisabled()).monitorPort(job.getMonitorPort()).monitorExecution(job.isMonitorExecution()).maxTimeDiffSeconds(job.getMaxTimeDiffSeconds()).jobShardingStrategyClass(job.getJobShardingStrategyClass()).reconcileIntervalMinutes(job.getReconcileIntervalMinutes()).build();
        BeanDefinitionBuilder factory = BeanDefinitionBuilder.rootBeanDefinition(SpringJobScheduler.class);
        factory.setScope("prototype");
        BeanDefinitionBuilder rdbFactory;
        //添加自己的真实的任务实现类
        if ("SCRIPT".equals(jobType)) {
            factory.addConstructorArgValue((Object) null);
        } else {
            rdbFactory = BeanDefinitionBuilder.rootBeanDefinition(job.getJobClass());
            factory.addConstructorArgValue(rdbFactory.getBeanDefinition());
        }
        //设置zk
        factory.addConstructorArgValue(this.zookeeperRegistryCenter);
        factory.addConstructorArgValue(jobConfig);
        //如果有eventTraceRdbDataSource 则也进行添加,记录任务的执行,需要db支持
        if (StringUtils.hasText(job.getEventTraceRdbDataSource())) {
            rdbFactory = BeanDefinitionBuilder.rootBeanDefinition(JobEventRdbConfiguration.class);
            rdbFactory.addConstructorArgReference(job.getEventTraceRdbDataSource());
            factory.addConstructorArgValue(rdbFactory.getBeanDefinition());
        }
        //添加监听
        List<BeanDefinition> elasticJobListeners = this.getTargetElasticJobListeners(job);
        factory.addConstructorArgValue(elasticJobListeners);
        String registerBeanName = job.getJobName() + "SpringJobScheduler";
        DefaultListableBeanFactory defaultListableBeanFactory = (DefaultListableBeanFactory) this.ctx.getAutowireCapableBeanFactory();
        defaultListableBeanFactory.registerBeanDefinition(registerBeanName, factory.getBeanDefinition());
        SpringJobScheduler springJobScheduler = (SpringJobScheduler) this.ctx.getBean(registerBeanName);
        springJobScheduler.init();
        this.logger.info("【" + job.getJobName() + "】\t" + job.getJobClass() + "\tinit success");
    }

    private List<BeanDefinition> getTargetElasticJobListeners(Job job) {
        List<BeanDefinition> result = new ManagedList(2);
        //添加每个分片都能执行一次的监听，一个任务只支持一个
        String listeners = job.getListener();
        if (StringUtils.hasText(listeners)) {
            BeanDefinitionBuilder factory = BeanDefinitionBuilder.rootBeanDefinition(listeners);
            factory.setScope("prototype");
            result.add(factory.getBeanDefinition());
        }

        //分布式集群中只执行一次的监听，一个任务只支持一个
        String distributedListeners = job.getDistributedListener();
        long startedTimeoutMilliseconds = job.getStartedTimeoutMilliseconds();
        long completedTimeoutMilliseconds = job.getCompletedTimeoutMilliseconds();
        if (StringUtils.hasText(distributedListeners)) {
            BeanDefinitionBuilder factory = BeanDefinitionBuilder.rootBeanDefinition(distributedListeners);
            factory.setScope("prototype");
            factory.addConstructorArgValue(startedTimeoutMilliseconds);
            factory.addConstructorArgValue(completedTimeoutMilliseconds);
            result.add(factory.getBeanDefinition());
        }
        return result;
    }
}
