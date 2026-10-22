package com.example.wallet;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class StatementQuestionController {
    private final StatementQuestionService service;
    public StatementQuestionController(StatementQuestionService service) { this.service=service; }
    @PostMapping("/users/{id}/statement/ask")
    public Map<String,Object> ask(@PathVariable long id,@RequestBody Map<String,String> body) {
        return service.ask(id,body.get("question"));
    }
}
