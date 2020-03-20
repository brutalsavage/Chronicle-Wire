package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesIn;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class YamlTokeniser {

    private static final int NO_INDENT = -1;
    static final Set<YamlToken> NO_TEXT = EnumSet.of(
            YamlToken.SEQUENCE_START,
            YamlToken.SEQUENCE_ENTRY,
            YamlToken.SEQUENCE_END,
            YamlToken.MAPPING_START,
            YamlToken.MAPPING_KEY,
            YamlToken.MAPPING_END,
            YamlToken.DIRECTIVES_END);

    private final BytesIn in;
    private final List<YTContext> contexts = new ArrayList<>();
    private final List<YTContext> freeContexts = new ArrayList<>();
    private YamlToken last = YamlToken.STREAM_START;
    Bytes temp = null;

    private final List<YamlToken> pushed = new ArrayList<>();
    long lineStart, blockStart, blockEnd;
    int flowDepth = Integer.MAX_VALUE;
    char blockQuote = 0;
    boolean hasSequenceEntry;
    long lastKeyPosition = -1;

    public YamlTokeniser(BytesIn in) {
        this.in = in;
        reset();
    }

    public int contextSize() {
        return contexts.size();
    }

    void reset() {
        contexts.forEach(freeContexts::add);
        contexts.clear();
        if (temp != null) temp.clear();
        lineStart = 0;
        flowDepth = Integer.MAX_VALUE;
        blockQuote = 0;
        hasSequenceEntry = false;
        lastKeyPosition = -1;
        last = YamlToken.STREAM_START;
        pushContext0(YamlToken.STREAM_START, NO_INDENT);
    }

    public YamlToken context() {
        return contexts.isEmpty() ? YamlToken.STREAM_START : topContext().token;
    }

    public YTContext topContext() {
        return contexts.get(contextSize() - 1);
    }

    public YamlToken current() {
        if (last == YamlToken.STREAM_START)
            return next();
        return last;
    }

    @NotNull
    public YamlToken next() {
        if (!pushed.isEmpty()) {
            YamlToken next = popPushed();
            return last = next;
        }
        YamlToken next = next0();
        return this.last = next;
    }

    YamlToken next0() {
        consumeWhitespace();
        blockStart = blockEnd = in.readPosition();
        if (temp != null)
            temp.clear();
        int indent = Math.toIntExact(in.readPosition() - lineStart);
        int ch = in.readUnsignedByte();
        switch (ch) {
            case -1:
                popAll(0);
                return popPushed();
            case '#':
                readComment();
                return YamlToken.COMMENT;
            case '"':
                lastKeyPosition = in.readPosition() - 1;
                readQuoted('"');
                if (isFieldEnd())
                    return indent(YamlToken.MAPPING_START, YamlToken.MAPPING_KEY, YamlToken.TEXT, indent * 2);
                return YamlToken.TEXT;
            case '\'':
                lastKeyPosition = in.readPosition() - 1;
                readQuoted('\'');
                if (isFieldEnd())
                    return indent(YamlToken.MAPPING_START, YamlToken.MAPPING_KEY, YamlToken.TEXT, indent * 2);

                return YamlToken.TEXT;

            case '?': {
                lastKeyPosition = in.readPosition() - 1;
                YamlToken indent2 = indent(YamlToken.MAPPING_START, YamlToken.MAPPING_KEY, YamlToken.STREAM_START, indent * 2);
                contextPush(YamlToken.MAPPING_KEY, indent * 2);
                return indent2;
            }

            case '-': {
                int next = in.peekUnsignedByte();
                if (next <= ' ') {
                    return indent(YamlToken.SEQUENCE_START, YamlToken.SEQUENCE_ENTRY, YamlToken.STREAM_START, indent * 2 + 1);
                }
                if (next == '-') {
                    if (in.peekUnsignedByte(in.readPosition() + 1) == '-' &&
                            in.peekUnsignedByte(in.readPosition() + 2) <= ' ') {
                        in.readSkip(2);
                        pushed.add(YamlToken.DIRECTIVES_END);
                        popAll(1);
                        contextPush(YamlToken.DIRECTIVES_END, NO_INDENT);
                        return popPushed();
                    }
                }
                unreadLast();
                return readText(indent);
            }
            case '.': {
                int next = in.peekUnsignedByte();
                if (next == '.') {
                    if (in.peekUnsignedByte(in.readPosition() + 1) == '.' &&
                            in.peekUnsignedByte(in.readPosition() + 2) <= ' ') {
                        in.readSkip(2);
                        popAll(1);
                        return popPushed();
                    }
                }
                unreadLast();
                return readText(indent);
            }
/* TODO
            case '&':
                if (in.peekUnsignedByte() > ' ') {
                    readAnchor();
                    return YamlToken.ANCHOR;
                }
                break;
            case '*':
                if (in.peekUnsignedByte() > ' ') {
                    readAnchor();
                    return YamlToken.ALIAS;
                }
*/
            case '|':
                if (in.peekUnsignedByte() <= ' ') {
                    readLiteral();
                    return seq(YamlToken.TEXT);
                }
                break;
            case '>':
                if (in.peekUnsignedByte() <= ' ') {
                    readFolded();
                    return seq(YamlToken.TEXT);
                }
            case '%':
                readDirective();
                return YamlToken.DIRECTIVE;
            case '@':
            case '`':
                readReserved();
                return seq(YamlToken.RESERVED);
            case '!':
                readWord();
                return seq(YamlToken.TAG);
            case '{':
                return flow(YamlToken.MAPPING_START);
            case '}':
                return flowPop(YamlToken.MAPPING_START, '}');
            case '[':
                hasSequenceEntry = false;
                return flow(YamlToken.SEQUENCE_START);
            case ']':
                return flowPop(YamlToken.SEQUENCE_START, ']');
            case ',':
                if (flowDepth >= contextSize())
                    flowDepth = contextSize();
                hasSequenceEntry = false;
                // CHECK in a LIST or MAPPING.
                return next0();

            case ':':
                if (in.peekUnsignedByte() <= ' ') {
                    int pos = pushed.size();
                    while (context() != YamlToken.MAPPING_KEY && contextSize() > 1) {
                        contextPop();
                    }
                    if (context() == YamlToken.MAPPING_KEY)
                        contextPop();
                    reversePushed(pos);
                    return pushed.isEmpty() ? next0() : popPushed();
                }
                // other symbols
            case '+':
            case '$':
            case '(':
            case ')':
            case '/':
            case ';':
            case '<':
            case '=':
            case '\\':
            case '^':
            case '_':
            case '~':
        }
        unreadLast();
        return readText(indent);
    }

    private YamlToken flowPop(YamlToken start, char end) {
        int pos = pushed.size();
        while (context() != start) {
            if (contextSize() <= 1)
                throw new IllegalArgumentException("Unexpected '" + end + '\'');
            contextPop();
        }
        contextPop();
        reversePushed(pos);
        return popPushed();
    }

    private YamlToken flow(YamlToken token) {
        pushed.add(token);
        if (!hasSequenceEntry && context() == YamlToken.SEQUENCE_START) {
            hasSequenceEntry = true;
            pushed.add(YamlToken.SEQUENCE_ENTRY);
        }
        contextPush(token, -1);
        if (flowDepth > contextSize())
            flowDepth = contextSize();
        return popPushed();
    }

    private void readReserved() {
        throw new UnsupportedOperationException();
    }

    private void readDirective() {
        readWords();
    }

    private void readFolded() {
        readLiteral(false);
    }

    private Bytes temp() {
        if (temp == null)
            temp = Bytes.elasticHeapByteBuffer(32);
        temp.clear();
        return temp;
    }

    private void readLiteral() {
        readLiteral(true);
    }

    private void readLiteral(boolean withNewLines) {
        readNewline(); // read to the end of the line.
        readIndent();
        int indent2 = Math.toIntExact(in.readPosition() - lineStart);
        blockStart = blockEnd = -1;
        Bytes temp = temp();
        long start = in.readPosition();
        while (true) {
            int ch = in.readUnsignedByte();
            if (ch < 0) {
                temp.write(in, start, in.readPosition() - start);
                break;
            }
            if (ch == '\r' || ch == '\n') {
                unreadLast();
                if (withNewLines)
                    readNewline();
                temp.write(in, start, in.readPosition() - start);
                if (!withNewLines)
                    if (temp.peekUnsignedByte(temp.writePosition() - 1) > ' ')
                        temp.append(' ');

                readIndent();
                int indent3 = Math.toIntExact(in.readPosition() - lineStart);
                if (indent3 < indent2)
                    return;
                if (indent3 > indent2)
                    in.readPosition(lineStart + indent2);
                start = in.readPosition();
            }
        }
    }

    private void readIndent() {
        while (true) {
            int ch = in.peekUnsignedByte();
            if (ch < 0 || ch > ' ')
                break;
            in.readSkip(1);
            if (ch == '\r' || ch == '\n')
                lineStart = in.readPosition();
        }
    }

    private void readNewline() {
        while (true) {
            int ch = in.peekUnsignedByte();
            if (ch < 0 || ch >= ' ')
                break;
            in.readSkip(1);
            lineStart = in.readPosition();
        }
    }

    private void readAnchor() {
        blockStart = in.readPosition();
        while (true) {
            blockEnd = in.readPosition();
            int ch = in.readUnsignedByte();
            if (ch <= ' ')
                return;
        }

    }

    private YamlToken indent(
            @NotNull YamlToken indented,
            @NotNull YamlToken key,
            @NotNull YamlToken push,
            int indent) {
        if (push != YamlToken.STREAM_START)
            this.pushed.add(push);
        if (isInFlow()) {
            return key;
        }
        int pos = this.pushed.size();
        while (indent < contextIndent()) {
            contextPop();
        }
        if (indent != contextIndent())
            this.pushed.add(indented);
        this.pushed.add(key);
        reversePushed(pos);
        if (indent > contextIndent())
            contextPush(indented, indent);
        return popPushed();
    }

    private YamlToken readText(int indent) {
        long pos = in.readPosition();

        blockQuote = 0;
        readWords();
        if (isFieldEnd()) {
            lastKeyPosition = pos;
            return indent(YamlToken.MAPPING_START, YamlToken.MAPPING_KEY, YamlToken.TEXT, indent * 2);
        }

        YamlToken token = YamlToken.TEXT;
        return seq(token);
    }

    private YamlToken seq(YamlToken token) {
        if (!hasSequenceEntry && context() == YamlToken.SEQUENCE_START && isInFlow()) {
            hasSequenceEntry = true;
            pushed.add(token);
            return YamlToken.SEQUENCE_ENTRY;
        }
        return token;
    }

    private void unreadLast() {
        in.readSkip(-1);
    }

    private int contextIndent() {
        return contexts.isEmpty() ? 0 : topContext().indent;
    }

    private boolean isInFlow() {
        return contextSize() >= flowDepth;
    }

    private void popAll(int downTo) {
        int pos = pushed.size();
        while (contextSize() > downTo) {
            contextPop();
        }
        reversePushed(pos);
    }

    private void reversePushed(int pos) {
        for (int i = pos, j = pushed.size() - 1; i < j; i++, j--)
            pushed.set(i, pushed.set(j, pushed.get(i)));
    }

    private YamlToken popPushed() {
        return pushed.isEmpty() ? YamlToken.STREAM_START : pushed.remove(pushed.size() - 1);
    }

    private void readWord() {
        blockStart = in.readPosition();
        boolean isQuote = in.peekUnsignedByte() == '<';
        while (true) {
            int ch = in.readUnsignedByte();
            // ! is valid in a type TAG
            // [] isn't standard but needed for array types in Java.
            if (ch <= ' ' || (!isQuote && ",{}:?'\"#".indexOf(ch) >= 0)) {
                unreadLast();
                break;
            }
            blockEnd = in.readPosition();
            if (isQuote && ch == '>') {
                blockStart++;
                blockEnd--;
                break;
            }
        }
    }

    private void readWords() {
        blockStart = in.readPosition();
        while (in.readRemaining() > 0) {
            int ch = in.readUnsignedByte();
            switch (ch) {
                case ':':
                    if (in.peekUnsignedByte() > ' ')
                        continue;
                    // is a field.
                    unreadLast();
                    return;
                case ',':
                    if (context() != YamlToken.SEQUENCE_START
                            && context() != YamlToken.MAPPING_START)
                        continue;
                    unreadLast();
                    return;

                case '[': {
                    long pos = in.readPosition();
                    if (in.peekUnsignedByte(pos - 2) > ' ' &&
                            in.peekUnsignedByte() == ']') {
                        in.readSkip(1);
                        blockEnd = pos + 1;
                        return;
                    }
                    unreadLast();
                    return;
                }
                case ']':
                case '{':
                case '}':
                case '#':
                case '\n':
                case '\r':
                    unreadLast();
                    return;
            }
            if (ch > ' ')
                blockEnd = in.readPosition();
        }
    }

    private void contextPop() {
        YTContext context0 = contexts.remove(contextSize() - 1);
        if (flowDepth == contextSize())
            flowDepth = Integer.MAX_VALUE;
        YamlToken toEnd = context0.token.toEnd;
        if (toEnd == null)
            throw new IllegalStateException("context: " + context0);
        if (toEnd != YamlToken.NONE)
            pushed.add(toEnd);
        freeContexts.add(context0);
    }

    private void contextPush(YamlToken context, int indent) {
        if (context() == YamlToken.STREAM_START && context != YamlToken.DIRECTIVES_END) {
            pushContext0(YamlToken.DIRECTIVES_END, NO_INDENT);
            pushContext0(context, indent);
            push(YamlToken.DIRECTIVES_END);
            return;
        }
        pushContext0(context, indent);
    }

    private void readQuoted(char stop) {
        blockQuote = stop;
        blockStart = in.readPosition();
        while (in.readRemaining() > 0) {
            int ch = in.readUnsignedByte();
            if (ch == '\\') {
                ch = in.readUnsignedByte();
            }
            if (ch == stop) {
                // ignore double single quotes.
                int ch2 = in.peekUnsignedByte();
                if (ch2 == stop) {
                    in.readSkip(1);
                    continue;
                }
                blockEnd = in.readPosition() - 1;
                return;
            }
            if (ch < 0) {
                throw new IllegalStateException("Unterminated quotes " + in.subBytes(blockStart - 1, in.readPosition()));
            }
        }
    }

    private boolean isFieldEnd() {
        consumeSpaces();
        if (in.peekUnsignedByte() == ':') {
            int ch = in.peekUnsignedByte(in.readPosition() + 1);
            in.readSkip((ch == '\t' || ch == ' ') ? 2 : 1);
            return true;
        }
        return false;
    }

    private void readComment() {
        in.readSkip(1);
        consumeSpaces();
        blockStart = blockEnd = in.readPosition();
        while (true) {
            int ch = in.readUnsignedByte();
            if (ch < 0)
                return;
            if (ch == '\n' || ch == '\r') {
                unreadLast();
                return;
            }
            if (ch > ' ')
                blockEnd = in.readPosition();
        }
    }

    private void consumeSpaces() {
        while (true) {
            int ch = in.peekUnsignedByte();
            if (ch == ' ' || ch == '\t') {
                in.readSkip(1);
            } else {
                return;
            }
        }
    }

    private void consumeWhitespace() {
        while (true) {
            int ch = in.peekUnsignedByte();
            if (ch >= 0 && ch <= ' ') {
                in.readSkip(1);
                if (ch == '\n' || ch == '\r')
                    lineStart = in.readPosition();
            } else {
                return;
            }
        }
    }

    public long lineStart() {
        return lineStart;
    }

    public long blockStart() {
        return blockStart;
    }

    public long blockEnd() {
        return blockEnd;
    }

    private void pushContext0(YamlToken token, int indent) {
        YTContext context = freeContexts.isEmpty() ? new YTContext() : freeContexts.remove(freeContexts.size() - 1);
        context.token = token;
        context.indent = indent;
        if (context.keys != null)
            context.keys.reset();
        contexts.add(context);
    }

    @Override
    public String toString() {
        String name = last.name();
        return last + " " + (blockQuote == 0 || name.endsWith("_START") || name.endsWith("_END") ? "" : blockQuote + " ") + text();
    }

    public char blockQuote() {
        return blockQuote;
    }

    // for testing.
    public String text() {
        StringBuilder sb = Wires.acquireStringBuilder();
        text(sb);
        return sb.length() == 0 ? "" : sb.toString();
    }

    public void text(StringBuilder sb) {
        if (blockEnd < 0 && temp != null) {
            sb.append(temp.toString());
            return;
        }
        sb.setLength(0);
        if (blockStart == blockEnd || NO_TEXT.contains(last))
            return;
        long pos = in.readPosition();
        in.readPosition(blockStart);
        in.parseUtf8(sb, Math.toIntExact(blockEnd - blockStart));
        in.readPosition(pos);
    }

    public void push(YamlToken token) {
        pushed.add(token);
    }


    public boolean isText(String s) {
        // TODO make more efficient.
        return text().equals(s);
    }

    public YamlKeys keys() {
        YTContext context = topContext();
        YamlKeys key = context.keys;
        if (key == null)
            return context.keys = new YamlKeys();
        return key;
    }

    public long lastKeyPosition() {
        return lastKeyPosition;
    }

    static class YTContext extends SelfDescribingMarshallable {
        YamlToken token;
        int indent;
        YamlKeys keys = null;
    }
}
