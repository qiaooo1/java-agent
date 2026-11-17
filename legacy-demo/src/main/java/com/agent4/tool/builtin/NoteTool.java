package com.agent4.tool.builtin;

import com.agent4.store.NoteDao;
import com.agent4.tool.Tool;
import com.agent4.tool.ToolParam;

import java.util.stream.Collectors;

/**
 * 笔记工具：Agent 可「记笔记」「查笔记」，笔记持久化到 SQLite。
 *
 * <p>这是体现「Agent + 持久化」结合的落地工具——Agent 不再只是无状态地
 * 回答问题，而是能主动把信息沉淀下来，下次查询还能读到。</p>
 */
public class NoteTool {

    private final NoteDao noteDao;

    public NoteTool(NoteDao noteDao) {
        this.noteDao = noteDao;
    }

    @Tool(name = "add_note", description = "记录一条笔记并保存到本地数据库（标题 + 内容）")
    public String addNote(
            @ToolParam("笔记标题") String title,
            @ToolParam("笔记内容") String content) {
        long id = noteDao.add(title, content);
        return "已保存笔记 #" + id + "：「" + title + "」";
    }

    @Tool(name = "list_notes", description = "查看最近保存的笔记列表")
    public String listNotes(
            @ToolParam(value = "返回条数，默认 10", required = false) Integer limit) {
        int n = limit == null ? 10 : Math.min(limit, 100);
        var notes = noteDao.listRecent(n);
        if (notes.isEmpty()) {
            return "还没有任何笔记";
        }
        return notes.stream()
                .map(note -> "#" + note.id() + " [" + note.title() + "] " + note.content())
                .collect(Collectors.joining("\n"));
    }
}
