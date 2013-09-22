/*
 * Copyright 2013 Yann Le Moigne
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

package fr.javatic.gradle.plugin.continuous

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService

import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection

class ContinuousTask extends DefaultTask {
	private def WatchService watcher
	private def ProjectConnection connection

	ContinuousTask() {
		this.watcher = FileSystems.getDefault().newWatchService();
		this.connection = GradleConnector.newConnector()
				.forProjectDirectory(project.projectDir)
				.connect();
	}

	@TaskAction
	def init() {
		def requestedTasks = project.continuous.tasks as Set

		def allTasks = getAllTasksInGraph(requestedTasks)
		def inputDirs = getInputDirs(allTasks)

		def leafTasks = getOnlyLeafTask(requestedTasks)
		installWatcher(inputDirs, leafTasks)
	}

	private def Set<Task> getAllTasksInGraph(Set<Task> tasks) {
		if (tasks.isEmpty()) []
		else tasks.collect { [it, getAllTasksInGraph(it.taskDependencies.getDependencies(it))] as Set }.flatten().unique()
	}

	private def Set<Task> getOnlyLeafTask(Set<Task> requestedTasks) {
		def dependentTask = requestedTasks.collect { it.taskDependencies.getDependencies(it) }.flatten().unique()

		requestedTasks - dependentTask
	}

	private def static Set<File> getInputDirs(Set<Task> tasks) {
		tasks.collect {
			it.inputs.files.files.collect {
				if (it.isDirectory()) it else it.parentFile
			}
		}.flatten().unique()
	}

	private def installWatcher(Set<File> inputs, Set<Task> tasks) {
		inputs.each {
			installWatch(it.toPath())
		}

		while (true) {
			def key = watcher.take()
			def watchEvents = key.pollEvents()

			watchEvents.findAll {
				it.kind() == StandardWatchEventKinds.ENTRY_DELETE
			}.collect {
				it.context() as Path
			}.findAll {
				Files.isDirectory(it)
			}.each {
				uninstallWatch(key)
			}

			watchEvents.findAll {
				it.kind() == StandardWatchEventKinds.ENTRY_CREATE
			}.collect {
				it.context() as Path
			}.findAll {
				Files.isDirectory(it)
			}.each {
				installWatch(it)
			}


			launchTasks(tasks)

			key.reset()
		}
	}

	private def installWatch(Path path) {
		logger.debug("Install watch on $path")

		path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
	}

	private def uninstallWatch(WatchKey key) {
		logger.debug("Remove watch on $path")

		key.cancel()
	}


	private def launchTasks(Set<Task> tasks) {
		logger.lifecycle("Launching tasks ${tasks.collect { it.name }.join(", ")})}")
		connection.newBuild().forTasks(tasks.collect { it.name } as String[]).run()
	}
}
