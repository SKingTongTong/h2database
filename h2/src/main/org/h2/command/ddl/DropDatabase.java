/*
 * Copyright 2004-2020 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.ddl;

import java.util.ArrayList;
import java.util.Collection;

import org.h2.command.CommandInterface;
import org.h2.engine.Database;
import org.h2.engine.DbObject;
import org.h2.engine.Role;
import org.h2.engine.SessionLocal;
import org.h2.engine.User;
import org.h2.schema.Schema;
import org.h2.schema.SchemaObject;
import org.h2.schema.Sequence;
import org.h2.table.Table;
import org.h2.table.TableType;

/**
 * This class represents the statement
 * DROP ALL OBJECTS
 */
public class DropDatabase extends DefineCommand {

    private boolean dropAllObjects;
    private boolean deleteFiles;

    public DropDatabase(SessionLocal session) {
        super(session);
    }

    @Override
    public int update() {
        if (dropAllObjects) {
            dropAllObjects();
        }
        if (deleteFiles) {
            session.getDatabase().setDeleteFilesOnDisconnect(true);
        }
        return 0;
    }

    private void dropAllObjects() {
        session.getUser().checkAdmin();
        session.commit(true);
        Database db = session.getDatabase();
        db.lockMeta(session);

        // There can be dependencies between tables e.g. using computed columns,
        // so we might need to loop over them multiple times.
        boolean runLoopAgain;
        do {
            ArrayList<Table> tables = db.getAllTablesAndViews(false);
            ArrayList<Table> toRemove = new ArrayList<>(tables.size());
            for (Table t : tables) {
                if (t.getName() != null &&
                        TableType.VIEW == t.getTableType()) {
                    toRemove.add(t);
                }
            }
            for (Table t : tables) {
                if (t.getName() != null &&
                        TableType.TABLE_LINK == t.getTableType()) {
                    toRemove.add(t);
                }
            }
            for (Table t : tables) {
                if (t.getName() != null &&
                        TableType.TABLE == t.getTableType() &&
                        !t.isHidden()) {
                    toRemove.add(t);
                }
            }
            for (Table t : tables) {
                if (t.getName() != null &&
                        TableType.EXTERNAL_TABLE_ENGINE == t.getTableType() &&
                        !t.isHidden()) {
                    toRemove.add(t);
                }
            }
            runLoopAgain = false;
            for (Table t : toRemove) {
                if (t.getName() == null) {
                    // ignore, already dead
                } else if (db.getDependentTable(t, t) == null) {
                    db.removeSchemaObject(session, t);
                } else {
                    runLoopAgain = true;
                }
            }
        } while (runLoopAgain);

        // TODO session-local temp tables are not removed
        Collection<Schema> schemas = db.getAllSchemasNoMeta();
        for (Schema schema : schemas) {
            if (schema.canDrop()) {
                db.removeDatabaseObject(session, schema);
            }
        }
        ArrayList<SchemaObject> list = new ArrayList<>();
        for (Schema schema : schemas) {
            for (Sequence sequence : schema.getAllSequences()) {
                // ignore these. the ones we want to drop will get dropped when we
                // drop their associated tables, and we will ignore the problematic
                // ones that belong to session-local temp tables.
                if (!sequence.getBelongsToTable()) {
                    list.add(sequence);
                }
            }
        }
        // maybe constraints and triggers on system tables will be allowed in
        // the future
        addAll(schemas, DbObject.CONSTRAINT, list);
        addAll(schemas, DbObject.TRIGGER, list);
        addAll(schemas, DbObject.CONSTANT, list);
        addAll(schemas, DbObject.FUNCTION_ALIAS, list);
        addAll(schemas, DbObject.AGGREGATE, list);
        addAll(schemas, DbObject.DOMAIN, list);
        for (SchemaObject obj : list) {
            if (!obj.getSchema().isValid() || obj.isHidden()) {
                continue;
            }
            db.removeSchemaObject(session, obj);
        }
        for (User user : db.getAllUsers()) {
            if (user != session.getUser()) {
                db.removeDatabaseObject(session, user);
            }
        }
        for (Role role : db.getAllRoles()) {
            String sql = role.getCreateSQL();
            // the role PUBLIC must not be dropped
            if (sql != null) {
                db.removeDatabaseObject(session, role);
            }
        }
        ArrayList<DbObject> dbObjects = new ArrayList<>();
        dbObjects.addAll(db.getAllRights());
        for (DbObject obj : dbObjects) {
            String sql = obj.getCreateSQL();
            // the role PUBLIC must not be dropped
            if (sql != null) {
                db.removeDatabaseObject(session, obj);
            }
        }
    }

    private static void addAll(Collection<Schema> schemas, int type, ArrayList<SchemaObject> list) {
        for (Schema schema : schemas) {
            schema.getAll(type, list);
        }
    }

    public void setDropAllObjects(boolean b) {
        this.dropAllObjects = b;
    }

    public void setDeleteFiles(boolean b) {
        this.deleteFiles = b;
    }

    @Override
    public int getType() {
        return CommandInterface.DROP_ALL_OBJECTS;
    }

}
