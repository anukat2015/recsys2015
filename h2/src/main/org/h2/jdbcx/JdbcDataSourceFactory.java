/*
 * Copyright 2004-2009 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.jdbcx;

import java.util.Hashtable;

//## Java 1.4 begin ##
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.Reference;
import javax.naming.spi.ObjectFactory;
//## Java 1.4 end ##

import org.h2.constant.SysProperties;
import org.h2.engine.Constants;
import org.h2.message.Trace;
import org.h2.message.TraceSystem;

/**
 * This class is used to create new DataSource objects.
 * An application should not use this class directly.
 */
public class JdbcDataSourceFactory
//## Java 1.4 begin ##
implements ObjectFactory
//## Java 1.4 end ##
{

    private static TraceSystem cachedTraceSystem;
    private Trace trace;

    static {
        org.h2.Driver.load();
    }

    /**
     * The public constructor to create new factory objects.
     */
    public JdbcDataSourceFactory() {
        trace = getTraceSystem().getTrace("JDBCX");
    }

    /**
     * Creates a new object using the specified location or reference
     * information.
     *
     * @param obj the reference (this factory only supports objects of type
     *            javax.naming.Reference)
     * @param name unused
     * @param nameCtx unused
     * @param environment unused
     * @return the new JdbcDataSource, or null if the reference class name is
     *         not JdbcDataSource.
     */
//## Java 1.4 begin ##
    public synchronized Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable< ? , ? > environment) {
        if (trace.isDebugEnabled()) {
            trace.debug("getObjectInstance obj=" + obj + " name=" + name + " nameCtx=" + nameCtx + " environment=" + environment);
        }
        Reference ref = (Reference) obj;
        if (ref.getClassName().equals(JdbcDataSource.class.getName())) {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL((String) ref.get("url").getContent());
            dataSource.setUser((String) ref.get("user").getContent());
            dataSource.setPassword((String) ref.get("password").getContent());
            String s = (String) ref.get("loginTimeout").getContent();
            dataSource.setLoginTimeout(Integer.parseInt(s));
            return dataSource;
        }
        return null;
    }
//## Java 1.4 end ##

    private TraceSystem getTraceSystem() {
        synchronized (JdbcDataSourceFactory.class) {
            if (cachedTraceSystem == null) {
                cachedTraceSystem = new TraceSystem(SysProperties.CLIENT_TRACE_DIRECTORY + "h2datasource" + Constants.SUFFIX_TRACE_FILE, false);
                cachedTraceSystem.setLevelFile(SysProperties.DATASOURCE_TRACE_LEVEL);
            }
            return cachedTraceSystem;
        }
    }

    Trace getTrace() {
        return trace;
    }

}
