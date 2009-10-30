/*
 * Copyright 2009-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.admin.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.admin.partition.remote.NoSuchStepExecutionException;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.ListableJobLocator;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.repository.dao.ExecutionContextDao;
import org.springframework.batch.core.step.NoSuchStepException;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;


// TODO: FactoryBean for this that includes table prefix
public class SimpleJobService implements JobService, DisposableBean {

	private static final Log logger = LogFactory.getLog(SimpleJobService.class);

	// 60 seconds
	private static final int DEFAULT_SHUTDOWN_TIMEOUT = 60 * 1000;

	private final SearchableJobInstanceDao jobInstanceDao;

	private final SearchableJobExecutionDao jobExecutionDao;

	private final JobRepository jobRepository;

	private final JobLauncher jobLauncher;

	private final ListableJobLocator jobLocator;

	private final SearchableStepExecutionDao stepExecutionDao;

	private final ExecutionContextDao executionContextDao;

	private Collection<JobExecution> activeExecutions = Collections.synchronizedList(new ArrayList<JobExecution>());

	private int shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

	/**
	 * Timeout for shutdown waiting for jobs to finish processing.
	 * 
	 * @param shutdownTimeout in milliseconds (default 60 secs)
	 */
	public void setShutdownTimeout(int shutdownTimeout) {
		this.shutdownTimeout = shutdownTimeout;
	}

	@Autowired
	public SimpleJobService(SearchableJobInstanceDao jobInstanceDao, SearchableJobExecutionDao jobExecutionDao,
			SearchableStepExecutionDao stepExecutionDao, JobRepository jobRepository, JobLauncher jobLauncher,
			ListableJobLocator jobLocator, ExecutionContextDao executionContextDao) {
		super();
		this.jobInstanceDao = jobInstanceDao;
		this.jobExecutionDao = jobExecutionDao;
		this.stepExecutionDao = stepExecutionDao;
		this.jobRepository = jobRepository;
		this.jobLauncher = jobLauncher;
		this.jobLocator = jobLocator;
		this.executionContextDao = executionContextDao;
	}

	public Collection<StepExecution> getStepExecutions(Long jobExecutionId) throws NoSuchJobExecutionException {

		JobExecution jobExecution = jobExecutionDao.getJobExecution(jobExecutionId);
		if (jobExecution == null) {
			throw new NoSuchJobExecutionException("No JobExecution with id=" + jobExecutionId);
		}

		stepExecutionDao.addStepExecutions(jobExecution);

		String jobName = jobExecution.getJobInstance() == null ? null : jobExecution.getJobInstance().getJobName();
		Collection<String> missingStepNames = new LinkedHashSet<String>();

		if (jobName != null) {
			missingStepNames.addAll(stepExecutionDao.findStepNamesForJobExecution(jobName, "*:partition*"));
			logger.debug("Found step executions in repository: " + missingStepNames);
		}

		Job job = null;
		try {
			job = jobLocator.getJob(jobName);
		}
		catch (NoSuchJobException e) {
			// expected
		}
		if (job instanceof StepLocator) {
			Collection<String> stepNames = ((StepLocator) job).getStepNames();
			missingStepNames.addAll(stepNames);
			logger.debug("Added step executions from job: " + missingStepNames);
		}

		for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
			String stepName = stepExecution.getStepName();
			if (missingStepNames.contains(stepName)) {
				missingStepNames.remove(stepName);
			}
			logger.debug("Removed step executions from job execution: " + missingStepNames);
		}

		for (String stepName : missingStepNames) {
			StepExecution stepExecution = jobExecution.createStepExecution(stepName);
			stepExecution.setStatus(BatchStatus.UNKNOWN);
		}

		return jobExecution.getStepExecutions();

	}

	public boolean isLaunchable(String jobName) {
		return jobLocator.getJobNames().contains(jobName);
	}

	public JobExecution restart(Long jobExecutionId) throws NoSuchJobExecutionException,
			JobExecutionAlreadyRunningException, JobRestartException, JobInstanceAlreadyCompleteException,
			NoSuchJobException {

		JobExecution target = getJobExecution(jobExecutionId);
		JobInstance lastInstance = target.getJobInstance();

		Job job = jobLocator.getJob(lastInstance.getJobName());

		JobExecution jobExecution = jobLauncher.run(job, lastInstance.getJobParameters());

		if (jobExecution.isRunning()) {
			activeExecutions.add(jobExecution);
		}
		return jobExecution;
	}

	public JobExecution launch(String jobName, JobParameters jobParameters) throws NoSuchJobException,
			JobExecutionAlreadyRunningException, JobRestartException, JobInstanceAlreadyCompleteException {

		Job job = jobLocator.getJob(jobName);

		if (job.getJobParametersIncrementer() != null) {

			Collection<JobInstance> lastInstances = listJobInstances(jobName, 0, 1);
			JobInstance lastInstance = null;
			if (!lastInstances.isEmpty()) {
				lastInstance = lastInstances.iterator().next();
			}
			
			JobParameters oldParameters = new JobParameters();
			if (lastInstance != null) {
				oldParameters = lastInstance.getJobParameters();
			}
			Map<String, JobParameter> parameters = new HashMap<String, JobParameter>(oldParameters.getParameters());
			parameters.putAll(jobParameters.getParameters());
			jobParameters = job.getJobParametersIncrementer().getNext(new JobParameters(parameters));

		}

		JobExecution jobExecution = jobLauncher.run(job, jobParameters);

		if (jobExecution.isRunning()) {
			activeExecutions.add(jobExecution);
		}
		return jobExecution;

	}

	public Collection<JobExecution> listJobExecutions(int start, int count) {
		return jobExecutionDao.getJobExecutions(start, count);
	}

	public int countJobExecutions() {
		return jobExecutionDao.countJobExecutions();
	}

	public Collection<String> listJobs(int start, int count) {
		Collection<String> jobNames = new LinkedHashSet<String>(jobLocator.getJobNames());
		if (start + count > jobNames.size()) {
			jobNames.addAll(jobInstanceDao.getJobNames());
		}
		if (start >= jobNames.size()) {
			start = jobNames.size();
		}
		if (start + count >= jobNames.size()) {
			count = jobNames.size() - start;
		}
		return new ArrayList<String>(jobNames).subList(start, start + count);
	}

	public int countJobs() {
		Collection<String> names = new HashSet<String>(jobLocator.getJobNames());
		names.addAll(jobInstanceDao.getJobNames());
		return names.size();
	}

	public int stopAll() {
		Collection<JobExecution> result = jobExecutionDao.getRunningJobExecutions();
		for (JobExecution jobExecution : result) {
			jobExecution.stop();
			jobRepository.update(jobExecution);
		}
		return result.size();
	}

	public JobExecution stop(Long jobExecutionId) throws NoSuchJobExecutionException, JobExecutionNotRunningException {

		JobExecution jobExecution = getJobExecution(jobExecutionId);
		if (!jobExecution.isRunning()) {
			throw new JobExecutionNotRunningException("JobExecution is not running and therefore cannot be stopped");
		}

		logger.info("Stopping job execution: " + jobExecution);
		jobExecution.stop();
		jobRepository.update(jobExecution);
		return jobExecution;

	}

	public JobExecution abandon(Long jobExecutionId) throws NoSuchJobExecutionException, JobExecutionAlreadyRunningException {

		JobExecution jobExecution = getJobExecution(jobExecutionId);
		if (jobExecution.getStatus().isLessThan(BatchStatus.STOPPING)) {
			throw new JobExecutionAlreadyRunningException("JobExecution is running or complete and therefore cannot be aborted");
		}

		logger.info("Aborting job execution: " + jobExecution);
		jobExecution.upgradeStatus(BatchStatus.ABANDONED);
		jobRepository.update(jobExecution);
		return jobExecution;

	}

	public int countJobExecutionsForJob(String name) throws NoSuchJobException {
		checkJobExists(name);
		return jobExecutionDao.countJobExecutions(name);
	}

	public int countJobInstances(String name) throws NoSuchJobException {
		return jobInstanceDao.countJobInstances(name);
	}

	public JobExecution getJobExecution(Long jobExecutionId) throws NoSuchJobExecutionException {
		JobExecution jobExecution = jobExecutionDao.getJobExecution(jobExecutionId);
		if (jobExecution == null) {
			throw new NoSuchJobExecutionException("There is no JobExecution with id=" + jobExecutionId);
		}
		jobExecution.setJobInstance(jobInstanceDao.getJobInstance(jobExecution));
		jobExecution.setExecutionContext(executionContextDao.getExecutionContext(jobExecution));
		stepExecutionDao.addStepExecutions(jobExecution);
		return jobExecution;
	}

	public Collection<JobExecution> getJobExecutionsForJobInstance(String name, Long jobInstanceId)
			throws NoSuchJobException {
		checkJobExists(name);
		List<JobExecution> jobExecutions = jobExecutionDao.findJobExecutions(jobInstanceDao
				.getJobInstance(jobInstanceId));
		for (JobExecution jobExecution : jobExecutions) {
			stepExecutionDao.addStepExecutions(jobExecution);
		}
		return jobExecutions;
	}

	public StepExecution getStepExecution(Long jobExecutionId, Long stepExecutionId)
			throws NoSuchJobExecutionException, NoSuchStepExecutionException {
		JobExecution jobExecution = getJobExecution(jobExecutionId);
		StepExecution stepExecution = stepExecutionDao.getStepExecution(jobExecution, stepExecutionId);
		if (stepExecution == null) {
			throw new NoSuchStepExecutionException("There is no StepExecution with jobExecutionId=" + jobExecutionId
					+ " and id=" + stepExecutionId);
		}
		stepExecution.setExecutionContext(executionContextDao.getExecutionContext(stepExecution));
		return stepExecution;
	}

	public Collection<JobExecution> listJobExecutionsForJob(String jobName, int start, int count)
			throws NoSuchJobException {
		checkJobExists(jobName);
		List<JobExecution> jobExecutions = jobExecutionDao.getJobExecutions(jobName, start, count);
		for (JobExecution jobExecution : jobExecutions) {
			stepExecutionDao.addStepExecutions(jobExecution);
		}
		return jobExecutions;
	}

	public Collection<StepExecution> listStepExecutionsForStep(String stepName, int start, int count)
			throws NoSuchStepException {
		if (stepExecutionDao.countStepExecutions(stepName) == 0) {
			throw new NoSuchStepException("No step executions exist with this step name: " + stepName);
		}
		return stepExecutionDao.findStepExecutions(stepName, start, count);
	}

	public int countStepExecutionsForStep(String stepName) throws NoSuchStepException {
		return stepExecutionDao.countStepExecutions(stepName);
	}

	public Collection<JobInstance> listJobInstances(String jobName, int start, int count) throws NoSuchJobException {
		checkJobExists(jobName);
		return jobInstanceDao.getJobInstances(jobName, start, count);
	}

	/**
	 * @param name
	 */
	private void checkJobExists(String jobName) throws NoSuchJobException {
		if (jobLocator.getJobNames().contains(jobName)) {
			return;
		}
		if (jobInstanceDao.countJobInstances(jobName) > 0) {
			return;
		}
		throw new NoSuchJobException("No Job with that name either current or historic: [" + jobName + "]");
	}

	/**
	 * Stop all the active jobs and wait for them (up to a time out) to finish
	 * processing.
	 */
	public void destroy() throws Exception {

		Exception firstException = null;

		for (JobExecution jobExecution : activeExecutions) {
			try {
				if (jobExecution.isRunning()) {
					stop(jobExecution.getId());
				}
			}
			catch (JobExecutionNotRunningException e) {
				logger.info("JobExecution is not running so it cannot be stopped");
			}
			catch (Exception e) {
				logger.error("Unexpected exception stopping JobExecution", e);
				if (firstException == null) {
					firstException = e;
				}
			}
		}

		int count = 0;
		int maxCount = (shutdownTimeout + 1000) / 1000;
		while (!activeExecutions.isEmpty() && ++count < maxCount) {
			logger.error("Waiting for " + activeExecutions.size() + " active executions to complete");
			removeInactiveExecutions();
			Thread.sleep(1000L);
		}

		if (firstException != null) {
			throw firstException;
		}

	}

	// TODO: schedule this for regular execution
	/**
	 * Check all the active executions and see if they are still actually
	 * running. Remove the ones that have completed.
	 */
	public void removeInactiveExecutions() {

		for (Iterator<JobExecution> iterator = activeExecutions.iterator(); iterator.hasNext();) {
			JobExecution jobExecution = iterator.next();
			try {
				jobExecution = getJobExecution(jobExecution.getId());
			}
			catch (NoSuchJobExecutionException e) {
				logger.error("Unexpected exception loading JobExecution", e);
			}
			if (!jobExecution.isRunning()) {
				iterator.remove();
			}
		}

	}

}