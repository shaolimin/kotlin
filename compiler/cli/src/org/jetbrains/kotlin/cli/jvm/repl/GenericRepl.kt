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

package org.jetbrains.kotlin.cli.jvm.repl

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.repl.*
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.repl.messages.DiagnosticMessageHolder
import org.jetbrains.kotlin.cli.jvm.repl.messages.ReplTerminalDiagnosticMessageHolder
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.descriptors.ScriptDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.script.KotlinScriptExternalDependencies
import java.io.File

private val logger = Logger.getInstance(GenericRepl::class.java)

open class GenericReplChecker(
        disposable: Disposable,
        val scriptDefinition: KotlinScriptDefinition,
        val compilerConfiguration: CompilerConfiguration,
        messageCollector: MessageCollector
) : ReplChecker {
    protected val environment = run {
        compilerConfiguration.apply {
            add(JVMConfigurationKeys.SCRIPT_DEFINITIONS, scriptDefinition)
            put<MessageCollector>(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
            put(JVMConfigurationKeys.RETAIN_OUTPUT_IN_MEMORY, true)
        }
        KotlinCoreEnvironment.createForProduction(disposable, compilerConfiguration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    }

    protected val psiFileFactory: PsiFileFactoryImpl = PsiFileFactory.getInstance(environment.project) as PsiFileFactoryImpl

    // "line" - is the unit of evaluation here, could in fact consists of several character lines
    protected class LineState(
            val codeLine: ReplCodeLine,
            val psiFile: KtFile,
            val errorHolder: DiagnosticMessageHolder)

    protected var lineState: LineState? = null

    fun createDiagnosticHolder() = ReplTerminalDiagnosticMessageHolder()

    @Synchronized
    override fun check(codeLine: ReplCodeLine, history: List<ReplCodeLine>): ReplCheckResult {
        val virtualFile =
                LightVirtualFile("line${codeLine.no}${KotlinParserDefinition.STD_SCRIPT_EXT}", KotlinLanguage.INSTANCE, codeLine.code).apply {
                    charset = CharsetToolkit.UTF8_CHARSET
                }
        val psiFile: KtFile = psiFileFactory.trySetupPsiForFile(virtualFile, KotlinLanguage.INSTANCE, true, false) as KtFile?
                              ?: error("Script file not analyzed at line ${codeLine.no}: ${codeLine.code}")

        val errorHolder = createDiagnosticHolder()

        val syntaxErrorReport = AnalyzerWithCompilerReport.Companion.reportSyntaxErrors(psiFile, errorHolder)

        if (!syntaxErrorReport.isHasErrors) {
            lineState = LineState(codeLine, psiFile, errorHolder)
        }

        return when {
            syntaxErrorReport.isHasErrors && syntaxErrorReport.isAllErrorsAtEof -> ReplCheckResult.Incomplete(history)
            syntaxErrorReport.isHasErrors -> ReplCheckResult.Error(history, errorHolder.renderedDiagnostics)
            else -> ReplCheckResult.Ok(history)
        }
    }
}


open class GenericReplCompiler(
        disposable: Disposable,
        scriptDefinition: KotlinScriptDefinition,
        compilerConfiguration: CompilerConfiguration,
        messageCollector: MessageCollector
) : ReplCompiler, GenericReplChecker(disposable, scriptDefinition, compilerConfiguration, messageCollector) {
    private val analyzerEngine = CliReplAnalyzerEngine(environment)

    private var lastDependencies: KotlinScriptExternalDependencies? = null

    private val descriptorsHistory = ReplHistory<ScriptDescriptor>()

    @Synchronized
    override fun compile(codeLine: ReplCodeLine, history: List<ReplCodeLine>): ReplCompileResult {
        checkAndUpdateReplHistoryCollection(descriptorsHistory, history)?.let {
            return@compile ReplCompileResult.HistoryMismatch(descriptorsHistory.lines, it)
        }

        val (psiFile, errorHolder) = run {
            if (lineState == null || lineState!!.codeLine != codeLine) {
                val res = check(codeLine, history)
                when (res) {
                    is ReplCheckResult.Incomplete -> return@compile ReplCompileResult.Incomplete(res.updatedHistory)
                    is ReplCheckResult.Error -> return@compile ReplCompileResult.Error(res.updatedHistory, res.message, res.location)
                    is ReplCheckResult.Ok -> {} // continue
                }
            }
            Pair(lineState!!.psiFile, lineState!!.errorHolder)
        }

        val newDependencies = scriptDefinition.getDependenciesFor(psiFile, environment.project, lastDependencies)
        var classpathAddendum: List<File>? = null
        if (lastDependencies != newDependencies) {
            lastDependencies = newDependencies
            classpathAddendum = newDependencies?.let { environment.updateClasspath(it.classpath.map(::JvmClasspathRoot)) }
        }

        val analysisResult = analyzerEngine.analyzeReplLine(psiFile, codeLine.no)
        AnalyzerWithCompilerReport.Companion.reportDiagnostics(analysisResult.diagnostics, errorHolder)
        val scriptDescriptor = when (analysisResult) {
            is CliReplAnalyzerEngine.ReplLineAnalysisResult.WithErrors -> return ReplCompileResult.Error(descriptorsHistory.lines, errorHolder.renderedDiagnostics)
            is CliReplAnalyzerEngine.ReplLineAnalysisResult.Successful -> analysisResult.scriptDescriptor
            else -> error("Unexpected result ${analysisResult.javaClass}")
        }

        val state = GenerationState(
                psiFile.project,
                ClassBuilderFactories.binaries(false),
                analyzerEngine.module,
                analyzerEngine.trace.bindingContext,
                listOf(psiFile),
                compilerConfiguration
        )
        state.replSpecific.scriptResultFieldName = SCRIPT_RESULT_FIELD_NAME
        state.replSpecific.earlierScriptsForReplInterpreter = descriptorsHistory.values
        state.beforeCompile()
        KotlinCodegenFacade.generatePackage(
                state,
                psiFile.script!!.getContainingKtFile().packageFqName,
                setOf(psiFile.script!!.getContainingKtFile()),
                org.jetbrains.kotlin.codegen.CompilationErrorHandler.THROW_EXCEPTION)

        descriptorsHistory.add(codeLine, scriptDescriptor)

        return ReplCompileResult.CompiledClasses(descriptorsHistory.lines,
                                                 state.factory.asList().map { CompiledClassData(it.relativePath, it.asByteArray()) },
                                                 state.replSpecific.hasResult,
                                                 classpathAddendum ?: emptyList())
    }

    companion object {
        private val SCRIPT_RESULT_FIELD_NAME = "\$\$result"
    }
}


open class GenericRepl(
        disposable: Disposable,
        scriptDefinition: KotlinScriptDefinition,
        compilerConfiguration: CompilerConfiguration,
        messageCollector: MessageCollector,
        baseClassloader: ClassLoader?,
        scriptArgs: Array<Any?>? = null,
        scriptArgsTypes: Array<Class<*>>? = null
) : ReplEvaluator, GenericReplCompiler(disposable, scriptDefinition, compilerConfiguration, messageCollector) {

    private val compiledEvaluator = GenericReplCompiledEvaluator(compilerConfiguration.jvmClasspathRoots, baseClassloader, scriptArgs, scriptArgsTypes)

    override val lastEvaluatedScript: ClassWithInstance? get() = compiledEvaluator.lastEvaluatedScript

    @Synchronized
    override fun eval(codeLine: ReplCodeLine, history: List<ReplCodeLine>, invokeWrapper: InvokeWrapper?): ReplEvalResult =
        compileAndEval(this, compiledEvaluator, codeLine, history, invokeWrapper)
}


fun compileAndEval(replCompiler: ReplCompiler, replCompiledEvaluator: ReplCompiledEvaluator, codeLine: ReplCodeLine, history: List<ReplCodeLine>, invokeWrapper: InvokeWrapper?): ReplEvalResult =
        replCompiler.compile(codeLine, history).let {
            when (it) {
                is ReplCompileResult.Incomplete -> ReplEvalResult.Incomplete(it.updatedHistory)
                is ReplCompileResult.HistoryMismatch -> ReplEvalResult.HistoryMismatch(it.updatedHistory, it.lineNo)
                is ReplCompileResult.Error -> ReplEvalResult.Error.CompileTime(it.updatedHistory, it.message, it.location)
                is ReplCompileResult.CompiledClasses -> replCompiledEvaluator.eval(codeLine, history, it.classes, it.hasResult, it.classpathAddendum, invokeWrapper)
            }
        }


