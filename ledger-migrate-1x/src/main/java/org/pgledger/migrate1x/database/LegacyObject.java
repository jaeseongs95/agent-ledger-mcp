package org.pgledger.migrate1x.database;

/**
 * One database object that the 1.x installer made and {@code retire-legacy} removes: a trigger, a view, a table, a function or
 * procedure, a sequence, the schema itself or a database role. The kinds are declared in the order the objects are dropped
 * ({@link LegacySchemaRepository}); a list sorted by {@link #kind()} and then by {@link #line()} is the same on every run, so
 * its digest can be confirmed (nothing in the order depends on the collation of a database).
 *
 * <p>Three shapes because what a {@code DROP} needs differs: a trigger is dropped on its table, a function or procedure by its
 * identity arguments (an overload of the same name is another object), everything else by its name.
 */
public sealed interface LegacyObject {

    /** The kinds, in the order of the drop: what depends on another kind is dropped before it. */
    enum Kind {
        TRIGGER, VIEW, TABLE, FUNCTION, PROCEDURE, SEQUENCE, SCHEMA, ROLE
    }

    Kind kind();

    /** The line that the list shows and the digest is made of, for example {@code TABLE defect}. */
    String line();

    /** A trigger of a table of the legacy schema. */
    record Trigger(String table, String name) implements LegacyObject {

        @Override
        public Kind kind() {
            return Kind.TRIGGER;
        }

        @Override
        public String line() {
            return "TRIGGER " + table + "." + name;
        }
    }

    /**
     * A function or a procedure with its identity arguments (the text {@code pg_get_function_identity_arguments} gives, for
     * example {@code p text, p_id integer}); {@code kind} is {@link Kind#FUNCTION} or {@link Kind#PROCEDURE}.
     */
    record Routine(Kind kind, String name, String arguments) implements LegacyObject {

        public Routine {
            if (kind != Kind.FUNCTION && kind != Kind.PROCEDURE) {
                throw new IllegalArgumentException("a routine is a function or a procedure: " + kind);
            }
        }

        @Override
        public String line() {
            return kind + " " + name + "(" + arguments + ")";
        }
    }

    /** A view, a table, a sequence, the schema or a role, by its name. */
    record Named(Kind kind, String name) implements LegacyObject {

        public Named {
            if (kind == Kind.TRIGGER || kind == Kind.FUNCTION || kind == Kind.PROCEDURE) {
                throw new IllegalArgumentException("a " + kind + " is not dropped by its name alone");
            }
        }

        @Override
        public String line() {
            return kind + " " + name;
        }
    }
}
