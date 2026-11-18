package com.ohcp.cropagent.ontology;
import com.ohcp.cropagent.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.io.IOException;
@RestController
@RequestMapping("/api/v1/ontology")
public class OntologyController {
 private final OntologyService service;
 public OntologyController(OntologyService service){this.service=service;}
 @GetMapping("/query/{type}")
 public ApiResponse<OntologyService.QueryResult> query(@PathVariable String type,@RequestParam String id) throws IOException {
  return ApiResponse.ok(service.query(type,id));
 }
 @ExceptionHandler(IllegalArgumentException.class)
 @ResponseStatus(HttpStatus.BAD_REQUEST)
 public ApiResponse<Void> invalid(IllegalArgumentException error){return ApiResponse.fail(1001,error.getMessage());}
}
