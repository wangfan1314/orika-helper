package com.github.wangfan1314.orikahelper.startup

import com.github.wangfan1314.orikahelper.listeners.CompilationFinishedListener
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // 注册编译完成监听器
        val compilerManager = CompilerManager.getInstance(project)
        compilerManager.addCompilationStatusListener(CompilationFinishedListener(project))
    }
}