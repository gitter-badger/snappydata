/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package dunit.standalone;

import com.gemstone.gemfire.internal.FileUtil;
import dunit.logging.log4j.ConfigLocator;
import dunit.RemoteDUnitVMIF;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.rmi.AccessException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author dsmith
 *
 */
public class ProcessManager {
  
  private int namingPort;
  private Map<Integer, ProcessHolder> processes = new HashMap<Integer, ProcessHolder>();
  private File log4jConfig;
  private int pendingVMs;
  private Registry registry;

  public ProcessManager(int namingPort, Registry registry) {
    this.namingPort = namingPort;
    this.registry = registry;
  }
  
  public void launchVMs() throws IOException, NotBoundException {
    log4jConfig = ConfigLocator.findConfigInWorkingDirectory();
  }

  public synchronized void launchVM(int vmNum) throws IOException {
    if(processes.containsKey(vmNum)) {
      throw new IllegalStateException("VM " + vmNum + " is already running.");
    }
    
    String[] cmd = buildJavaCommand(vmNum, namingPort);
    System.out.println("Executing " + Arrays.asList(cmd));
    File workingDir = getVMDir(vmNum);
    try {
      FileUtil.delete(workingDir);
    } catch(IOException e) {
      //This delete is occasionally failing on some platforms, maybe due to a lingering
      //process. Allow the process to be launched anyway.
      System.err.println("Unable to delete " + workingDir + ". Currently contains " 
                          + Arrays.asList(workingDir.list()));
    }
    workingDir.mkdirs();
    if (log4jConfig != null) {
      FileUtils.copyFileToDirectory(log4jConfig, workingDir);
    }
    
    //TODO - delete directory contents, preferably with commons io FileUtils
    Process process = Runtime.getRuntime().exec(cmd, null, workingDir);
    pendingVMs++;
    ProcessHolder holder = new ProcessHolder(process);
    processes.put(vmNum, holder);
    linkStreams(vmNum, holder, process.getErrorStream(), System.err);
    linkStreams(vmNum, holder, process.getInputStream(), System.out);
  }

  public static File getVMDir(int vmNum) {
    return new File(DUnitLauncher.DUNIT_DIR, "vm" + vmNum);
  }
  
  public synchronized void killVMs() {
    for(ProcessHolder process : processes.values()) {
      if(process != null) {
        //TODO - stop it gracefully? Why bother
        process.kill();
      }
    }
  }
  
  public synchronized void bounce(int vmNum) {
    if(!processes.containsKey(vmNum)) {
      throw new IllegalStateException("No such process " + vmNum);
    }
    try {
      ProcessHolder holder = processes.remove(vmNum);
      holder.kill();
      holder.getProcess().waitFor();
      launchVM(vmNum);
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException("Unable to restart VM " + vmNum, e);
    }
  }
   
  private void linkStreams(final int vmNum, final ProcessHolder holder, final InputStream in, final PrintStream out) {
    Thread ioTransport = new Thread() {
      public void run() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String vmName = (vmNum==-2)? "[locator]" : "[vm_"+vmNum+"]";
        try {
          String line = reader.readLine();
          while(line != null) {
            out.print(vmName);
            out.println(line);
            line = reader.readLine();
          }
        } catch(Exception e) {
          if(!holder.isKilled()) {
            out.println("Error transporting IO from child process");
            e.printStackTrace(out);
          }
        }
      }
    };

    ioTransport.setDaemon(true);
    ioTransport.start();
  }

  private String[] buildJavaCommand(int vmNum, int namingPort) {
    String cmd = System.getProperty( "java.home" ) + File.separator + "bin" + File.separator + "java";
    String classPath = System.getProperty("java.class.path");
    //String tmpDir = System.getProperty("java.io.tmpdir");
    String agent = getAgentString();
    return new String[] {
      cmd, "-classpath", classPath,
      "-D" + DUnitLauncher.RMI_PORT_PARAM + "=" + namingPort,
      "-D" + DUnitLauncher.VM_NUM_PARAM + "=" + vmNum,
      "-D" + DUnitLauncher.WORKSPACE_DIR_PARAM + "=" + new File(".").getAbsolutePath(),
      "-DlogLevel=" + DUnitLauncher.LOG_LEVEL,
      "-Djava.library.path=" + System.getProperty("java.library.path"),
      "-Xrunjdwp:transport=dt_socket,server=y,suspend=n",
      "-XX:+HeapDumpOnOutOfMemoryError",
      "-Xmx512m",
      "-XX:MaxPermSize=256M",
      "-Dgemfire.DEFAULT_MAX_OPLOG_SIZE=10",
      "-Dgemfire.disallowMcastDefaults=true",
      "-XX:MaxPermSize=256M",
      "-Djava.net.preferIPv4Stack=true",
      "-ea",
      agent,
      "dunit.standalone.ChildVM"
    };
  }
  
  /**
   * Get the java agent passed to this process and pass it to the child VMs.
   * This was added to support jacoco code coverage reports
   */
  private String getAgentString() {
    RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    if (runtimeBean != null) {
      for(String arg: runtimeBean.getInputArguments()) {
        if(arg.contains("-javaagent:")) {
          //HACK for gradle bug  GRADLE-2859. Jacoco is passing a relative path
          //That won't work when we pass this to dunit VMs in a different 
          //directory
          arg = arg.replace("-javaagent:..", "-javaagent:" + System.getProperty("user.dir") + File.separator + "..");
          arg = arg.replace("destfile=..", "destfile=" + System.getProperty("user.dir") + File.separator + "..");
          return arg;
        }
      }
    }
    
    return "-DdummyArg=true";
  }

  synchronized void signalVMReady() {
    pendingVMs--;
    this.notifyAll();
  }
  
  public synchronized boolean waitForVMs(long timeout) throws InterruptedException {
    long end = System.currentTimeMillis() + timeout;
    while(pendingVMs > 0) {
      long remaining = end - System.currentTimeMillis();
      if(remaining <= 0) {
        return false;
      }
      this.wait(remaining);
    }
    
    return true;
  }
  
  private static class ProcessHolder {
    private final Process process;
    private volatile boolean killed = false;
    
    public ProcessHolder(Process process) {
      this.process = process;
    }

    public void kill() {
      this.killed = true;
      process.destroy();
      
    }

    public Process getProcess() {
      return process;
    }

    public boolean isKilled() {
      return killed;
    }
  }

  public RemoteDUnitVMIF getStub(int i) throws AccessException, RemoteException, NotBoundException, InterruptedException {
    waitForVMs(DUnitLauncher.STARTUP_TIMEOUT);
    return (RemoteDUnitVMIF) registry.lookup("vm" + i);
  }
}
