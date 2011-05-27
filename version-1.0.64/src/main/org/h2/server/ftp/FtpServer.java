/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.server.ftp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Properties;

import org.h2.engine.Constants;
import org.h2.server.Service;
import org.h2.store.fs.FileSystem;
import org.h2.util.FileUtils;
import org.h2.util.IOUtils;
import org.h2.util.MathUtils;
import org.h2.util.NetUtils;

/**
 * Small FTP Server. Intended for ad-hoc networks in a secure environment.
 * Remote connections are possible.
 * See also http://cr.yp.to/ftp.html http://www.ftpguide.com/
 */
public class FtpServer implements Service {

    public static final String DEFAULT_ROOT = "ftp";
    public static final String DEFAULT_READ = "guest";
    public static final String DEFAULT_WRITE = "sa";
    public static final String DEFAULT_WRITE_PASSWORD = "sa";

    private ServerSocket serverSocket;
    private int port = Constants.DEFAULT_FTP_PORT;
    private int openConnectionCount;
    private int maxConnectionCount = 100;

    private SimpleDateFormat dateFormatNew = new SimpleDateFormat("MMM dd HH:mm", Locale.ENGLISH);
    private SimpleDateFormat dateFormatOld = new SimpleDateFormat("MMM dd  yyyy", Locale.ENGLISH);
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");

    private String root = DEFAULT_ROOT;
    private String writeUserName = DEFAULT_WRITE, writePassword = DEFAULT_WRITE_PASSWORD;
    private String readUserName = DEFAULT_READ;
    private HashMap tasks = new HashMap();

    private FileSystem fs;
    private boolean log;
    private boolean allowTask;
    static final String TASK_SUFFIX = ".task";

    private FtpEventListener eventListener;

    public void listen() {
        try {
            while (serverSocket != null) {
                Socket s = serverSocket.accept();
                boolean stop;
                synchronized (this) {
                    openConnectionCount++;
                    stop = openConnectionCount > maxConnectionCount;
                }
                FtpControl c = new FtpControl(s, this, stop);
                c.start();
            }
        } catch (Exception e) {
            logError(e);
        }
    }

    void closeConnection() {
        synchronized (this) {
            openConnectionCount--;
        }
    }

    public ServerSocket createDataSocket() throws IOException {
        ServerSocket dataSocket = new ServerSocket(0);
        return dataSocket;
    }

    void appendFile(StringBuffer buff, String fileName) throws SQLException {
        buff.append(fs.isDirectory(fileName) ? 'd' : '-');
        buff.append('r');
        buff.append(fs.canWrite(fileName) ? 'w' : '-');
        buff.append("------- 1 owner group ");
        String size = String.valueOf(fs.length(fileName));
        for (int i = size.length(); i < 15; i++) {
            buff.append(' ');
        }
        buff.append(size);
        buff.append(' ');
        Date now = new Date(), mod = new Date(fs.getLastModified(fileName));
        String date;
        if (mod.after(now) || Math.abs((now.getTime() - mod.getTime()) / 1000 / 60 / 60 / 24) > 180) {
            synchronized (dateFormatOld) {
                date = dateFormatOld.format(mod);
            }
        } else {
            synchronized (dateFormatNew) {
                date = dateFormatNew.format(mod);
            }
        }
        buff.append(date);
        buff.append(' ');
        buff.append(FileUtils.getFileName(fileName));
        buff.append("\r\n");
    }

    String formatLastModified(String fileName) {
        synchronized (dateFormat) {
            return dateFormat.format(new Date(fs.getLastModified(fileName)));
        }
    }

    String getFileName(String path) {
        return root + getPath(path);
    }

    String getPath(String path) {
        if (path.indexOf("..") > 0) {
            path = "/";
        }
        while (path.startsWith("/") && root.endsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        log("path: " + path);
        return path;
    }

    String getDirectoryListing(String directory, boolean listDirectories) throws SQLException {
        String[] list = fs.listFiles(directory);
        StringBuffer buff = new StringBuffer();
        for (int i = 0; list != null && i < list.length; i++) {
            String fileName = list[i];
            if (!fs.isDirectory(fileName) || (fs.isDirectory(fileName) && listDirectories)) {
                appendFile(buff, fileName);
            }
        }
        return buff.toString();
    }

    public boolean checkUserPassword(String userName, String password) {
        return userName.equals(this.writeUserName) && password.equals(this.writePassword);
    }

    public boolean checkUserPasswordReadOnly(String userName, String param) {
        return userName.equals(this.readUserName);
    }

    public void init(String[] args) throws Exception {
        for (int i = 0; args != null && i < args.length; i++) {
            String a = args[i];
            if ("-ftpPort".equals(a)) {
                port = MathUtils.decodeInt(args[++i]);
            } else if ("-ftpDir".equals(a)) {
                root = FileUtils.normalize(args[++i]);
            } else if ("-ftpRead".equals(a)) {
                readUserName = args[++i];
            } else if ("-ftpWrite".equals(a)) {
                writeUserName = args[++i];
            } else if ("-ftpWritePassword".equals(a)) {
                writePassword = args[++i];
            } else if ("-log".equals(a)) {
                log = Boolean.valueOf(args[++i]).booleanValue();
            } else if ("-ftpTask".equals(a)) {
                allowTask = Boolean.valueOf(args[++i]).booleanValue();
            }
        }
        fs = FileSystem.getInstance(root);
        root = fs.normalize(root);
    }

    public String getURL() {
        return "ftp://localhost:" + port;
    }

    public void start() throws SQLException {
        fs.mkdirs(root);
        serverSocket = NetUtils.createServerSocket(port, false);
    }

    public void stop() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            logError(e);
        }
        serverSocket = null;
    }

    public boolean isRunning() {
        if (serverSocket == null) {
            return false;
        }
        try {
            Socket s = NetUtils.createLoopbackSocket(port, false);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean getAllowOthers() {
        return true;
    }

    public String getType() {
        return "FTP";
    }

    public String getName() {
        return "H2 FTP Server";
    }

    void log(String s) {
        if (log) {
            System.out.println(s);
        }
    }

    void logError(Throwable e) {
        if (log) {
            e.printStackTrace();
        }
    }

    public boolean getAllowTask() {
        return allowTask;
    }

    void startTask(String path) throws IOException {
        stopTask(path);
        if (path.endsWith(".zip.task")) {
            log("expand: " + path);
            Process p = Runtime.getRuntime().exec("jar -xf " + path, null, new File(root));
            new StreamRedirect(path, p.getInputStream(), null).start();
            return;
        }
        Properties prop = FileUtils.loadProperties(path);
        String command = prop.getProperty("command");
        String outFile = path.substring(0, path.length() - TASK_SUFFIX.length());
        String errorFile = root + "/" + prop.getProperty("error", outFile + ".err.txt");
        String outputFile = root + "/" + prop.getProperty("output", outFile + ".out.txt");
        log("start process: " + path + " / " + command);
        Process p = Runtime.getRuntime().exec(command, null, new File(root));
        new StreamRedirect(path, p.getErrorStream(), errorFile).start();
        new StreamRedirect(path, p.getInputStream(), outputFile).start();
        tasks.put(path, p);
    }

    private static class StreamRedirect extends Thread {
        private InputStream in;
        private OutputStream out;
        private String outFile;
        private String processFile;

        StreamRedirect(String processFile, InputStream in, String outFile) {
            this.processFile = processFile;
            this.in = in;
            this.outFile = outFile;
        }

        private void openOutput() {
            if (outFile != null) {
                try {
                    this.out = FileUtils.openFileOutputStream(outFile, false);
                } catch (Exception e) {
                    // ignore
                }
                outFile = null;
            }
        }

        public void run() {
            while (true) {
                try {
                    int x = in.read();
                    if (x < 0) {
                        break;
                    }
                    openOutput();
                    if (out != null) {
                        out.write(x);
                    }
                } catch (IOException e) {
                    // ignore
                }
            }
            IOUtils.closeSilently(out);
            IOUtils.closeSilently(in);
            new File(processFile).delete();
        }
    }

    void stopTask(String processName) {
        log("kill process: " + processName);
        Process p = (Process) tasks.remove(processName);
        if (p == null) {
            return;
        }
        p.destroy();
    }

    /**
     * Get the file system used by this FTP server.
     *
     * @return the file system
     */
    public FileSystem getFileSystem() {
        return fs;
    }

    /**
     * Set the event listener. Only one listener can be registered.
     *
     * @param eventListener the new listener, or null to de-register
     */
    public void setEventListener(FtpEventListener eventListener) {
        this.eventListener = eventListener;
    }

    /**
     * Get the registered event listener.
     *
     * @return the event listener, or null if non is registered
     */
    public FtpEventListener getEventListener() {
        return eventListener;
    }

}
