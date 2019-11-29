package org.apache.tomee;

import java.io.PrintStream;
import java.net.URLStreamHandlerFactory;
import java.security.Policy;

/**
 * @author gaellalire
 */
public interface TomEEVestigeSystem {

    void setURLStreamHandlerFactory(URLStreamHandlerFactory urlStreamHandlerFactory);

    void setOut(PrintStream out);

    void setErr(PrintStream err);

    PrintStream getOut();

    PrintStream getErr();

    void setPolicy(Policy policy);

    Policy getPolicy();

}
