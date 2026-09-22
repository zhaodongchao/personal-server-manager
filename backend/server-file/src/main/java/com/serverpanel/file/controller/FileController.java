package com.serverpanel.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.PageQuery;
import com.serverpanel.common.core.PageResult;
import com.serverpanel.common.core.R;
import com.serverpanel.file.dto.FileBodies.CompressBody;
import com.serverpanel.file.dto.FileBodies.ContentBody;
import com.serverpanel.file.dto.FileBodies.ExtractBody;
import com.serverpanel.file.dto.FileBodies.MkdirBody;
import com.serverpanel.file.dto.FileBodies.MoveBody;
import com.serverpanel.file.dto.FileBodies.PermBody;
import com.serverpanel.file.dto.FileBodies.RenameBody;
import com.serverpanel.file.dto.FileEntry;
import com.serverpanel.file.entity.FileRecycleBin;
import com.serverpanel.file.service.FileService;
import com.serverpanel.file.service.RecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件管理器接口。
 *
 * <p>安全约束：所有路径经 {@code PathGuard} 白名单 + realPath 校验；
 * 写操作全部带 @Audit 审计；删除/清空回收站标记高危。
 */
@RestController
@RequestMapping("/api/v1/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final RecycleService recycleService;

    // ==================== 查询 ====================

    /** 白名单根目录 */
    @SaCheckPermission("file:list")
    @GetMapping("/roots")
    public R<List<FileEntry>> roots() {
        return R.ok(fileService.roots());
    }

    /** 目录内容 */
    @SaCheckPermission("file:list")
    @GetMapping("/list")
    public R<List<FileEntry>> list(@RequestParam String path) {
        return R.ok(fileService.list(path));
    }

    /** 在线编辑内容 */
    @SaCheckPermission("file:edit")
    @GetMapping("/content")
    public R<String> content(@RequestParam String path) {
        return R.ok(fileService.content(path));
    }

    /** 下载 */
    @SaCheckPermission("file:list")
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String path) {
        return fileService.download(path);
    }

    // ==================== 写操作 ====================

    @Audit(module = "file", action = "mkdir")
    @SaCheckPermission("file:mkdir")
    @PostMapping("/mkdir")
    public R<Void> mkdir(@Valid @RequestBody MkdirBody body) {
        fileService.mkdir(body.path());
        return R.ok();
    }

    @Audit(module = "file", action = "rename")
    @SaCheckPermission("file:edit")
    @PostMapping("/rename")
    public R<Void> rename(@Valid @RequestBody RenameBody body) {
        fileService.rename(body.path(), body.newName());
        return R.ok();
    }

    @Audit(module = "file", action = "move")
    @SaCheckPermission("file:edit")
    @PostMapping("/move")
    public R<Void> move(@Valid @RequestBody MoveBody body) {
        fileService.move(body.sourcePath(), body.targetDir());
        return R.ok();
    }

    @Audit(module = "file", action = "upload")
    @SaCheckPermission("file:upload")
    @PostMapping("/upload")
    public R<Integer> upload(@RequestParam String dir,
            @RequestParam("files") MultipartFile[] files) {
        return R.ok(fileService.upload(dir, files));
    }

    @Audit(module = "file", action = "edit")
    @SaCheckPermission("file:edit")
    @PutMapping("/content")
    public R<Void> updateContent(@Valid @RequestBody ContentBody body) {
        fileService.updateContent(body.path(), body.content());
        return R.ok();
    }

    @Audit(module = "file", action = "perm")
    @SaCheckPermission("file:perm")
    @PostMapping("/perm")
    public R<Void> perm(@Valid @RequestBody PermBody body) {
        fileService.perm(body.path(), body.mode());
        return R.ok();
    }

    @Audit(module = "file", action = "compress")
    @SaCheckPermission("file:compress")
    @PostMapping("/compress")
    public R<Void> compress(@Valid @RequestBody CompressBody body) {
        fileService.compress(body.path(), body.format());
        return R.ok();
    }

    @Audit(module = "file", action = "extract")
    @SaCheckPermission("file:compress")
    @PostMapping("/extract")
    public R<Void> extract(@Valid @RequestBody ExtractBody body) {
        fileService.extract(body.archivePath(), body.targetDir());
        return R.ok();
    }

    @Audit(module = "file", action = "delete", risky = true)
    @SaCheckPermission("file:delete")
    @DeleteMapping
    public R<Void> delete(@RequestParam String path) {
        recycleService.delete(path);
        return R.ok();
    }

    // ==================== 回收站 ====================

    @SaCheckPermission("file:list")
    @GetMapping("/recycle")
    public R<PageResult<FileRecycleBin>> recycle(PageQuery query,
            @RequestParam(required = false) String keyword) {
        return R.ok(recycleService.page(query, keyword));
    }

    @Audit(module = "file", action = "recycle:restore", risky = true)
    @SaCheckPermission("file:delete")
    @PostMapping("/recycle/restore/{id}")
    public R<Void> restore(@PathVariable Long id) {
        recycleService.restore(id);
        return R.ok();
    }

    @Audit(module = "file", action = "recycle:purge", risky = true)
    @SaCheckPermission("file:delete")
    @DeleteMapping("/recycle/{id}")
    public R<Void> purge(@PathVariable Long id) {
        recycleService.purge(id);
        return R.ok();
    }

    // safe = true：清空回收站不可逆，作为二级认证（step-up）的试点端点。
    // 其余高危端点的开通清单待确认后逐个加 safe = true，一行即可。
    @Audit(module = "file", action = "recycle:empty", risky = true, safe = true)
    @SaCheckPermission("file:delete")
    @PostMapping("/recycle/empty")
    public R<Void> empty() {
        recycleService.empty();
        return R.ok();
    }
}
