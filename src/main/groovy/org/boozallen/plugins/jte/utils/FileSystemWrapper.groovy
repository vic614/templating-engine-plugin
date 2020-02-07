/*
   Copyright 2018 Booz Allen Hamilton

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package org.boozallen.plugins.jte.utils


import org.boozallen.plugins.jte.console.TemplateLogger

import org.jenkinsci.plugins.workflow.job.WorkflowJob

import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty
import jenkins.branch.Branch
import hudson.model.ItemGroup
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMRevision
import jenkins.scm.api.SCMFileSystem
import jenkins.scm.api.SCMSource
import jenkins.scm.api.SCMFile
import hudson.scm.SCM

// heavy checkout
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TopLevelItem;
import hudson.model.Computer;
import org.jenkinsci.plugins.workflow.steps.scm.GenericSCMStep;
import org.jenkinsci.plugins.workflow.steps.scm.SCMStep;
import hudson.slaves.WorkspaceList;
import hudson.Functions;
import hudson.AbortException;
import jenkins.model.Jenkins;



import hudson.model.TaskListener
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.flow.FlowDefinition


class FileSystemWrapper {
    SCM scm 
    SCMFileSystem fs
    String scmKey 

    FileSystemWrapper(){}

    static FileSystemWrapper createFromSCM(SCM scm){
        FileSystemWrapper fsw = new FileSystemWrapper()
        fsw.scm = scm
        fsw.fsFromSCM(scm)
        return fsw
    }

    static FileSystemWrapper createFromJob(WorkflowJob job = RunUtils.getJob()){
        FileSystemWrapper fsw = new FileSystemWrapper()
        fsw.fsFrom(job)
        return fsw
    }

    /*
        retrieves a SCMFile if present.
        if ignoreMissing = true, missing files arent logged. 
        returns null if file not present
    */
    String getFileContents(String filePath, String loggingDescription = null, Boolean logMissingFile = true) {
        if (!fs) {
            TemplateLogger.printWarning("FileSystemWrapper does not have SCMFileSystem, cannot obtain ${loggingDescription} ${filePath}")
            TemplateLogger.printWarning("Resorting to heavyweight checkout")
            TemplateLogger.printWarning("SCM = ${this.scm}")
            throw new AbortException("fin")
            return heavyWeightCheckout(filePath, loggingDescription, logMissingFile)
        }

        try {
            SCMFile f = fs.child(filePath)
            if (!f.exists()) {
                if (logMissingFile) {
                    TemplateLogger.printWarning("""${filePath} does not exist.
                                                -- scm: ${scmKey}""", true)
                }
                return null
            }
            if (!f.isFile()) {
                TemplateLogger.printWarning("""${filePath} exists but is not a file.
                                            -- scm: ${scmKey}""", true)
                return null
            }
            if (loggingDescription){
                TemplateLogger.print("""Obtained ${loggingDescription}
                                        -- scm: ${scmKey}
                                        -- file path: ${filePath}""", [initiallyHidden:true])
            }

            return f.contentAsString()
        } catch(java.io.FileNotFoundException fne){
            if (logMissingFile) {
                TemplateLogger.printWarning("""${filePath} threw FileNotFoundException.
                                                -- scm: ${scmKey}""", true)
            }
            return null
        }
        finally {
            fs.close()
        }

    }

    String heavyWeightCheckout(String filePath, String loggingDescription = null, Boolean logMissingFile = true){
        FilePath dir;
        Node node = Jenkins.get();
        TaskListener listener = RunUtils.getListener()
        if (RunUtils.getBuild().getParent() instanceof TopLevelItem) {
            FilePath baseWorkspace = node.getWorkspaceFor((TopLevelItem) RunUtils.getBuild().getParent());
            if (baseWorkspace == null) {
                throw new IOException(node.getDisplayName() + " may be offline");
            }
            dir = baseWorkspace.withSuffix(System.getProperty(WorkspaceList.class.getName(), "@") + "script");
        } else { // should not happen, but just in case:
            dir = new FilePath(owner.getRootDir());
        }
        TemplateLogger.print("Checking out " + scm.getKey() + " into " + dir + " to read " + filePath);
        String script = null;
        Computer computer = node.toComputer();
        if (computer == null) {
            throw new IOException(node.getDisplayName() + " may be offline");
        }
        SCMStep delegate = new GenericSCMStep(scm);
        delegate.setPoll(true);
        delegate.setChangelog(true);
        FilePath acquiredDir;
        try {
            WorkspaceList.Lease lease = computer.getWorkspaceList().acquire(dir)
            int numRetry = (Jenkins.get().getScmCheckoutRetryCount() > 0) ? Jenkins.get().getScmCheckoutRetryCount() : 1; 
            for (int retryCount = numRetry; retryCount >= 0; retryCount--) {
                try {
                    delegate.checkout(RunUtils.getBuild(), dir, listener, node.createLauncher(listener));
                    break;
                } catch (AbortException e) {
                    // abort exception might have a null message.
                    // If so, just skip echoing it.
                    if (e.getMessage() != null) {
                        listener.error(e.getMessage());
                    }
                } catch (InterruptedIOException e) {
                    throw e;
                } catch (Exception e) {
                    // checkout error not yet reported
                    Functions.printStackTrace(e, listener.error("Checkout failed"));
                }

                if (retryCount == 0)   // all attempts failed
                    throw new AbortException("Maximum checkout retry attempts reached, aborting");

                TemplateLogger.print("Retrying after 10 seconds");
                Thread.sleep(10000);
            }

            dir.list().each{ file -> 
                TemplateLogger.print(" -> ${file}")
            }

            // FilePath scriptFile = dir.child(filePath);
            // if (!scriptFile.absolutize().getRemote().replace('\\', '/').startsWith(dir.absolutize().getRemote().replace('\\', '/') + '/')) { // TODO JENKINS-26838
            //     throw new IOException(scriptFile + " is not inside " + dir);
            // }
            // if (!scriptFile.exists()) {
            //     throw new AbortException(scriptFile + " not found");
            // }
            // script = scriptFile.readToString();
            // acquiredDir = lease.path;
        }catch(any){
            TemplateLogger.printError(any.getMessage())
        }
        return "mockstring"
    }



    def fsFromSCM(SCM scm, WorkflowJob job = RunUtils.getJob()){
        if(!scm || !job){
            return [null, null]
        }

        try{
            scmKey = scm.getKey()
            fs = SCMFileSystem.of(job,scm)
            return [fs, scmKey]
        }catch(any){
            TemplateLogger.printWarning(any.toString())
            return [null, null]
        }
    }

    /*
        return[0]: SCMFileSystem
        return[1]: String: key from scm
    */
    def fsFrom(WorkflowJob job){
        ItemGroup<?> parent = job.getParent()
        TaskListener listener = RunUtils.getListener()


        try {
            if (parent instanceof WorkflowMultiBranchProject) {
                // ensure branch is defined
                BranchJobProperty property = job.getProperty(BranchJobProperty.class)
                if (!property) {
                    throw new JTEException("inappropriate context") // removed IllegalStateEx as an example
                }
                Branch branch = property.getBranch()

                // get scm source for specific branch and ensure present
                // (might not be if branch deleted after job triggered)
                SCMSource scmSource = parent.getSCMSource(branch.getSourceId())
                TemplateLogger.printWarning("scmSource = ${scmSource}")
                if (!scmSource) {
                    throw new JTEException(new IllegalStateException("${branch.getSourceId()} not found"))
                }

                SCMHead head = branch.getHead()
                SCMRevision tip = scmSource.fetch(head, listener)

                TemplateLogger.printWarning("SCMHead = ${head}")
                TemplateLogger.printWarning("SCMRevision = ${tip}")

                this.scm = branch.getScm()
                scmKey = scm.getKey()

                if (tip) {
                    SCMRevision rev = scmSource.getTrustedRevision(tip, listener)
                    fs = SCMFileSystem.of(scmSource, head, rev)
                    return [fs, scmKey]
                } else {
                    fs = SCMFileSystem.of(job, scm)
                    return [fs, scmKey]
                }
            } else {
                FlowDefinition definition = job.getDefinition()
                if (definition instanceof CpsScmFlowDefinition) {
                    this.scm = definition.getScm()
                    scmKey = this.scm.getKey()
                    fs = SCMFileSystem.of(job, scm)
                    return [fs, scmKey]
                } else {
                    return [fs, scmKey]
                }
            }
        }catch(JTEException jteex){//throw our exception
            throw (jteex.cause ?: jteex)
        }catch(any){// ignore but print every other exception
            TemplateLogger.printWarning(any.toString())
        }

        return [fs, scmKey]
    }


    Object asType(Class clazz) {
        if( null != fs && clazz.isInstance(fs)){
            return fs
        }

        if( clazz.isInstance(this)){
            return this
        }

        return null
    }

}