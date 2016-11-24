package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.io.IORuntimeException;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.nio.ByteBuffer;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Created by dsmith on 11/11/16.
 */
public class WireBug38Test {
    static class MarshallableObj implements Marshallable {
        private final StringBuilder builder = new StringBuilder();

        public void clear() {
            builder.setLength(0);
        }

        public void append(CharSequence cs) {
            builder.append(cs);
        }

        @Override
        public void readMarshallable(WireIn wire) throws IORuntimeException {
            builder.setLength(0);
            assertNotNull(wire.getValueIn().textTo(builder));
        }

        @Override
        public void writeMarshallable(WireOut wire) {
            wire.getValueOut().text(builder);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            MarshallableObj that = (MarshallableObj) o;

            return builder.toString().equals(that.builder.toString());
        }

        @Override
        public int hashCode() {
            return builder.toString().hashCode();
        }

        @Override
        public String toString() {
            return builder.toString();
        }
    }

    static class Outer implements Marshallable {
        private final MarshallableObj obj = new MarshallableObj();

        MarshallableObj getObj() {
            return obj;
        }

        @Override
        public void readMarshallable(@NotNull WireIn wire) throws IORuntimeException {
            wire.read(() -> "obj").marshallable(obj);
        }

        @Override
        public void writeMarshallable(@NotNull WireOut wire) {
            wire.write(() -> "obj").marshallable(obj);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Outer outer = (Outer) o;

            return obj.equals(outer.obj);
        }

        @Override
        public int hashCode() {
            return obj.hashCode();
        }
    }

    @Test
    public void testNestedObj() {
        final WireType wireType = WireType.TEXT;
        final String exampleString = "{";

        final Outer obj1 = new Outer();
        final Outer obj2 = new Outer();

        obj1.getObj().append(exampleString);

        final Bytes<ByteBuffer> bytes = Bytes.elasticByteBuffer();
        obj1.writeMarshallable(wireType.apply(bytes));

        final String output = bytes.toString();
        System.out.println("output: [" + output + "]");

        obj2.readMarshallable(wireType.apply(Bytes.from(output)));

        assertEquals(obj1, obj2);
    }
}