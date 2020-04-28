/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.DbObjectType;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.schema.Constant;
import org.lealone.db.schema.Schema;
import org.lealone.db.session.ServerSession;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DROP CONSTANT
 * 
 * @author H2 Group
 * @author zhh
 */
public class DropConstant extends SchemaStatement {

    private String constantName;
    private boolean ifExists;

    public DropConstant(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.DROP_CONSTANT;
    }

    public void setConstantName(String constantName) {
        this.constantName = constantName;
    }

    public void setIfExists(boolean b) {
        ifExists = b;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        synchronized (schema.getLock(DbObjectType.CONSTANT)) {
            Constant constant = schema.findConstant(constantName);
            if (constant == null) {
                if (!ifExists) {
                    throw DbException.get(ErrorCode.CONSTANT_NOT_FOUND_1, constantName);
                }
            } else {
                schema.remove(session, constant);
            }
        }
        return 0;
    }
}
