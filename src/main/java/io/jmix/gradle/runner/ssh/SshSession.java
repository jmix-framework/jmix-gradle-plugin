/*
 * Copyright 2021 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jmix.gradle.runner.ssh;

import com.jcraft.jsch.*;
import io.jmix.gradle.runner.InstanceState;

import java.io.*;

public class SshSession implements AutoCloseable {

    private static final int BUFFER_SIZE = 1024;

    private final Session session;

    public static SshSession forInstance(InstanceState instance) throws JSchException {
        JSch jsch = new JSch();
        jsch.addIdentity(instance.getKeyFile());
        Session session = jsch.getSession(instance.getUsername(), instance.getHost());
        session.setConfig("StrictHostKeyChecking", "no");
        return new SshSession(session);
    }

    private SshSession(Session session) {
        this.session = session;
    }

    @Override
    public void close() {
        if (session.isConnected()) {
            session.disconnect();
        }
    }

    public void execute(String command) throws IOException, JSchException {
        execute(command, null, System.out);
    }

    public void execute(String command, InputStream inputStream) throws IOException, JSchException {
        execute(command, inputStream, System.out);
    }

    public void execute(String command, InputStream inputStream, OutputStream outputStream) throws JSchException, IOException {
        if (!session.isConnected()) {
            session.connect();
        }
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            if (inputStream != null) {
                channel.setInputStream(inputStream);
            }
            channel.connect();

            InputStream in = channel.getInputStream();

            byte[] buf = new byte[BUFFER_SIZE];
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(buf, 0, BUFFER_SIZE);
                    if (i < 0) break;
                    if (outputStream != null) {
                        outputStream.write(buf, 0, i);
                    }
                }
                if (outputStream != null) {
                    outputStream.flush();
                }
                if (channel.isClosed()) {
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    public void scpUploadFile(File fromFile, String targetPath) throws JSchException, IOException {
        if (!session.isConnected()) {
            session.connect();
        }

        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("scp -t " + targetPath);

            // get I/O streams for remote scp
            OutputStream out = channel.getOutputStream();
            InputStream in = channel.getInputStream();

            channel.connect();

            checkAck(in);

            // send "C0644 filesize filename", where filename should not include '/'
            String command = "C0644 " + fromFile.length() + " " + fromFile.getName() + "\n";
            out.write(command.getBytes());
            out.flush();

            checkAck(in);

            // send a content of a file
            try (FileInputStream fis = new FileInputStream(fromFile)) {
                byte[] buf = new byte[1024];
                while (true) {
                    int len = fis.read(buf, 0, buf.length);
                    if (len <= 0) break;
                    out.write(buf, 0, len);
                }

                // send '\0'
                buf[0] = 0;
                out.write(buf, 0, 1);
                out.flush();

                checkAck(in);

                out.close();
            }
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    public static void checkAck(InputStream in) throws IOException {
        int b = in.read();
        // b may be 0 for success,
        //          1 for error,
        //          2 for fatal error,
        //         -1
        if (b == 0) {
            return;
        }

        if (b == 1 || b == 2) {
            StringBuilder sb = new StringBuilder();
            int c;
            do {
                c = in.read();
                sb.append((char) c);
            } while (c != '\n');
            throw new RuntimeException(sb.toString());
        }

        throw new RuntimeException("Failed to receive ACK");
    }
}
