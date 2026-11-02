package com.jfx.git.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The program git and ssh run when they need to ask the user something.
 *
 * <p>Started as a separate JVM by the {@code GIT_ASKPASS} / {@code SSH_ASKPASS} script
 * {@link AskpassBridge} writes, or - for Windows' own OpenSSH, which can start only a real
 * executable - by a packaged build's console launcher
 * ({@link AskpassBridge#NATIVE_HELPER_NAME}). It carries no UI of its own: it hands the
 * prompt to the running application over a loopback socket, prints whatever answer comes
 * back, and
 * exits - 0 with the answer on standard output, which is how both git and ssh read an
 * askpass program, or 1 when the user cancelled or the application could not be reached.
 *
 * <p>Deliberately dependent on {@code java.base} alone and on no other class in this
 * application, so the JVM that runs it loads almost nothing and starts quickly, and so
 * it works from a bare classpath entry.
 */
public final class AskpassMain {

    /** Environment variables the bridge sets for the git process; ssh and git pass them on. */
    static final String PORT_ENV = "LYRA_ASKPASS_PORT";
    static final String TOKEN_ENV = "LYRA_ASKPASS_TOKEN";
    /** Set to {@code 1} for a git command nobody is watching: its credential lookups may not prompt. */
    static final String CREDENTIAL_SILENT_ENV = "LYRA_CREDENTIAL_SILENT";
    /** The request kind prefix for git's credential-helper protocol; see {@link #credential}. */
    static final String CREDENTIAL_KIND = "credential-";

    private AskpassMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length > 0 && "--credential".equals(args[0])) {
            return credential(args.length > 1 ? args[1] : "", System.in);
        }
        String prompt = args.length > 0 ? args[0] : "";
        String port = System.getenv(PORT_ENV);
        String token = System.getenv(TOKEN_ENV);
        if (port == null || token == null) return 1;
        // OpenSSH says what kind of question this is when it knows: "confirm" wants a
        // yes/no answer through the exit status, "none" is informational.
        String kind = System.getenv("SSH_ASKPASS_PROMPT");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    Integer.parseInt(port.trim())), 5_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF(token);
            out.writeUTF(kind == null ? "" : kind);
            out.writeUTF(prompt);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            boolean answered = in.readBoolean();
            if (!answered) return 1;
            String answer = in.readUTF();
            // Raw bytes through System.out, which is not ours to close: git reads the
            // answer as UTF-8 whatever this JVM's console encoding happens to be.
            System.out.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            return 0;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Acts as git's credential helper: {@code get}, {@code store} or {@code erase}, with
     * git's {@code key=value} block on standard input.
     *
     * <p>Forwarded to the application like a prompt, with this process's working directory
     * - git runs its helpers in the repository, so the application can consult the user's
     * own helpers with that repository's configuration. A {@code get} answer is printed for
     * git to read; nothing printed means "not known here", and git carries on to its
     * askpass prompt. Always exits 0: a helper that fails makes git complain on every
     * operation, and a missing answer already says everything.
     */
    static int credential(String operation, java.io.InputStream stdin) {
        String request;
        try {
            request = new String(stdin.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return 0;
        }
        String port = System.getenv(PORT_ENV);
        String token = System.getenv(TOKEN_ENV);
        if (port == null || token == null) return 0;
        boolean silent = "1".equals(System.getenv(CREDENTIAL_SILENT_ENV));
        String kind = CREDENTIAL_KIND + operation + (silent ? "-silent" : "");
        String body = "cwd=" + System.getProperty("user.dir", "") + "\n" + request;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    Integer.parseInt(port.trim())), 5_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF(token);
            out.writeUTF(kind);
            out.writeUTF(body);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            if (in.readBoolean() && "get".equals(operation)) {
                System.out.write(in.readUTF().getBytes(StandardCharsets.UTF_8));
                System.out.flush();
            }
        } catch (Exception e) {
            // Not reachable: git goes on without this helper's answer.
        }
        return 0;
    }
}
