import * as vscode from "vscode";

export interface GradleTaskDefinition extends vscode.TaskDefinition {
    id: string;
    script: string;
    description: string;
    group: string;
    project: string;
    rootProject: string;
    buildFile: string;
    projectFolder: string;
    workspaceFolder: string;
    debugEnabled: boolean;
    args: string;
    javaDebug: boolean;
}
