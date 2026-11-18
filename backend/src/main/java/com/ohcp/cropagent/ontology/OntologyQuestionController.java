package com.ohcp.cropagent.ontology;
import com.ohcp.cropagent.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.io.IOException;
@RestController
@RequestMapping("/api/v1/ontology")
public class OntologyQuestionController {
    private final OntologyQuestionService service;
    public OntologyQuestionController(OntologyQuestionService service) { this.service=service; }
    @PostMapping("/question")
    public ApiResponse<OntologyQuestionService.Answer> question(@Valid @RequestBody OntologyQuestionService.Question question) throws IOException {
        return ApiResponse.ok(service.ask(question));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalid(Exception error) { return ApiResponse.fail(1001,"请填写作物名称和 4–500 字的症状描述。"); }
    @ExceptionHandler({IOException.class, org.apache.jena.shared.JenaException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> unavailable(Exception error) { return ApiResponse.fail(2001,"本体服务暂不可用，请稍后重试。"); }
}
