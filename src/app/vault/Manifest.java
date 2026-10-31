package app.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 索引（文件名、大小等）的二进制序列化。序列化结果会整体加密后再写盘。 */
final class Manifest {
    private static final byte[] MAGIC = {'V', 'B', 'M', '1'};

    private Manifest() {}

    static byte[] serialize(List<Entry> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + entries.size() * 96);
        DataOutputStream out = new DataOutputStream(bos);
        out.write(MAGIC);
        out.writeInt(entries.size());
        for (Entry e : entries) {
            out.writeUTF(e.id());
            out.writeUTF(e.path());
            out.writeLong(e.size());
            out.writeLong(e.mtime());
            out.writeByte(e.kind().ordinal());
        }
        out.flush();
        return bos.toByteArray();
    }

    static List<Entry> deserialize(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("索引格式不正确");
        int count = in.readInt();
        if (count < 0 || count > 2_000_000) throw new IOException("索引条目数量异常");
        List<Entry> list = new ArrayList<>(Math.min(count, 100_000));
        Entry.Kind[] kinds = Entry.Kind.values();
        for (int i = 0; i < count; i++) {
            String id = in.readUTF();
            String path = in.readUTF();
            long size = in.readLong();
            long mtime = in.readLong();
            int k = in.readUnsignedByte();
            Entry.Kind kind = k < kinds.length ? kinds[k] : Entry.kindOf(path);
            list.add(new Entry(id, path, size, mtime, kind));
        }
        return list;
    }
}
