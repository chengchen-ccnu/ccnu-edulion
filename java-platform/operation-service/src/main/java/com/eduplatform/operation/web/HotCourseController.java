package com.eduplatform.operation.web;

import com.eduplatform.operation.hot.HotCourseCache;
import com.eduplatform.operation.hot.HotCourseModels.Board;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 首页公开热点只读入口；输入无身份，输出Redis投影/隔离降级，不触发SQL、HTTP或重建。 */
@RestController
public class HotCourseController {
    private final HotCourseCache cache;
    public HotCourseController(HotCourseCache cache) {this.cache=cache;}
    /** 返回最多六门课程及可判别缓存状态；签名URL禁止HTTP缓存，不授报名、正文或媒体权限。 */
    @GetMapping("/api/v1/operations/hot-courses")
    public ResponseEntity<Board> hot() {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cache.read());}
}
