/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.noarg.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.AbstractTask
import org.gradle.api.internal.ConventionTask
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.gradle.plugin.KotlinGradleSubplugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class NoArgGradlePlugin : Plugin<Project> {
    companion object {
        fun isEnabled(project: Project) = project.plugins.findPlugin(NoArgGradlePlugin::class.java) != null

        fun getNoArgExtension(project: Project): NoArgExtension {
            return project.extensions.getByType(NoArgExtension::class.java)
        }
    }

    override fun apply(project: Project) {
        val noArgExtension = project.extensions.create("noArg", NoArgExtension::class.java)

        project.afterEvaluate {
            val fqNamesAsString = noArgExtension.myAnnotations.joinToString(",")
            project.extensions.extraProperties.set("kotlinNoArgAnnotations", fqNamesAsString)

            open class TaskForNoArg : AbstractTask()
            project.tasks.add(project.tasks.create("noArgDataStorageTask", TaskForNoArg::class.java).apply {
                isEnabled = false
                description = "Supported no-arg annotations: " + fqNamesAsString
            })
        }
    }
}

class NoArgKotlinGradleSubplugin : KotlinGradleSubplugin<AbstractCompile> {
    private companion object {
        val ANNOTATIONS_ARG_NAME = "annotations"
    }

    override fun isApplicable(project: Project, task: AbstractCompile) = NoArgGradlePlugin.isEnabled(project)

    override fun apply(
            project: Project,
            kotlinCompile: AbstractCompile,
            javaCompile: AbstractCompile,
            variantData: Any?,
            javaSourceSet: SourceSet?
    ): List<SubpluginOption> {
        if (!NoArgGradlePlugin.isEnabled(project)) return emptyList()

        val noArgExtension = project.extensions.findByType(NoArgExtension::class.java) ?: return emptyList()

        val options = mutableListOf<SubpluginOption>()

        for (anno in noArgExtension.myAnnotations) {
            options += SubpluginOption(ANNOTATIONS_ARG_NAME, anno)
        }

        return options
    }

    override fun getArtifactName() = "kotlin-noarg"
    override fun getGroupName() = "org.jetbrains.kotlin"
    override fun getPluginName() = "org.jetbrains.kotlin.noarg"
}