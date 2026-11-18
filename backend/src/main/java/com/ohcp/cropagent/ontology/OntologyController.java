package com.ohcp.cropagent.ontology;
import com.ohcp.cropagent.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import jakarta.validation.Valid;
@RestController
@RequestMapping("/api/v1/ontology")
public class OntologyController {
 private final OntologyService service;
 private final OntologyReasoningService reasoning;
 public OntologyController(OntologyService service, OntologyReasoningService reasoning){this.service=service;this.reasoning=reasoning;}
 @GetMapping("/query/{type}")
 public ApiResponse<OntologyService.QueryResult> query(@PathVariable String type,@RequestParam String id) throws IOException {
  return ApiResponse.ok(service.query(type,id));
 }
 @PostMapping("/analyze")
 public ApiResponse<OntologyReasoningService.Result> analyze(@Valid @RequestBody OntologyReasoningService.Request request) throws IOException {
  return ApiResponse.ok(reasoning.analyze(request));
 }
 @ExceptionHandler(IllegalArgumentException.class)
 @ResponseStatus(HttpStatus.BAD_REQUEST)
 public ApiResponse<Void> invalid(IllegalArgumentException error){return ApiResponse.fail(1001,error.getMessage());}
 @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
         org.springframework.web.bind.MethodArgumentNotValidException.class})
 @ResponseStatus(HttpStatus.BAD_REQUEST)
 public ApiResponse<Void> invalidBody(Exception error){return ApiResponse.fail(1001,"请提供合法的作物标识和 1–10 条部位、形态观察。");}
 @ExceptionHandler({IOException.class, org.apache.jena.shared.JenaException.class, IllegalStateException.class})
 @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
 public ApiResponse<Void> unavailable(Exception error){return ApiResponse.fail(2001,"本体资产或规则暂不可用，请检查服务配置后重试。");}
}
