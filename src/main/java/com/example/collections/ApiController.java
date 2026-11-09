package com.example.collections;

import jakarta.validation.Valid;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api")
class ApiController {
    private static final ZoneId KAMPALA=ZoneId.of("Africa/Kampala");
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final EntryService entryService;
    private final EntryRepository entries;
    private final RestClient importClient;

    ApiController(AccountService accountService,AccountRepository accounts,EntryService entryService,
                  EntryRepository entries,@Value("${app.import-service-url}") String importUrl) {
        this.accountService=accountService; this.accounts=accounts; this.entryService=entryService; this.entries=entries;
        this.importClient=RestClient.builder().baseUrl(importUrl).build();
    }

    @PostMapping("/auth/register")
    ResponseEntity<Map<String,Object>> register(@Valid @RequestBody RegisterRequest request) {
        Account account=accountService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("username",account.username,"role",account.role));
    }
    @PostMapping("/auth/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) { return accountService.login(request); }
    @PostMapping("/auth/password")
    ResponseEntity<Void> changePassword(Authentication auth,@Valid @RequestBody PasswordRequest request) {
        accountService.changePassword(auth.getName(),request); return ResponseEntity.noContent().build();
    }
    @GetMapping("/auth/me")
    Map<String,Object> me(Authentication auth) {
        Account account=current(auth);
        return Map.of("id",account.id,"username",account.username,"displayName",account.displayName,
                "role",account.role,"mustChangePassword",account.mustChangePassword);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/accounts")
    ResponseEntity<Map<String,Object>> createAccount(Authentication auth,@Valid @RequestBody AccountRequest request) {
        Account created=accountService.create(request,current(auth).id);
        return ResponseEntity.status(HttpStatus.CREATED).body(accountView(created));
    }
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/accounts")
    List<Map<String,Object>> listAccounts() {
        return accounts.findAll(Sort.by("username")).stream().map(this::accountView).toList();
    }
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/accounts/{id}/enabled")
    ResponseEntity<Void> enableAccount(Authentication auth,@PathVariable long id,@RequestBody Map<String,Boolean> body) {
        Account actor=current(auth);
        if(actor.id.equals(id)&&Boolean.FALSE.equals(body.get("enabled")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cannot disable your own account");
        accountService.setEnabled(id,Boolean.TRUE.equals(body.get("enabled")),actor.id); return ResponseEntity.noContent().build();
    }
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/accounts/{id}/password")
    ResponseEntity<Void> resetPassword(Authentication auth,@PathVariable long id,@Valid @RequestBody ResetPasswordRequest request) {
        accountService.resetPassword(id,request.temporaryPassword(),current(auth).id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/entries")
    ResponseEntity<EntryView> createEntry(Authentication auth,@Valid @RequestBody EntryRequest request,
                                          @RequestParam(required=false) Long officerId) {
        Account actor=current(auth);
        Account owner=actor;
        if("ADMIN".equals(actor.role)&&officerId!=null) {
            owner=accounts.findById(officerId).filter(account->"OFFICER".equals(account.role)&&account.enabled)
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an active officer"));
        } else if(!"ADMIN".equals(actor.role)&&officerId!=null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Officers can create records only for themselves");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(entryService.create(request,owner.username,actor.id));
    }
    @org.springframework.web.bind.annotation.PutMapping("/entries/{id}")
    EntryView updateEntry(Authentication auth,@PathVariable long id,@Valid @RequestBody EntryRequest request) {
        Account actor=current(auth);
        return entryService.update(id,request,"ADMIN".equals(actor.role),actor.username,actor.id);
    }
    @GetMapping("/entries")
    Page<EntryView> listEntries(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,
            @RequestParam(required=false) String q,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) Long officerId) {
        Account actor=current(auth);
        return entryService.list(actor.username,"ADMIN".equals(actor.role),page,size,q,from,to,officerId);
    }
    @GetMapping("/entries/totals")
    Map<String,String> filteredTotals(Authentication auth,@RequestParam(required=false) String q,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) Long officerId) {
        Account actor=current(auth);
        return Map.of("amount",Long.toString(entryService.filteredTotal(actor.username,"ADMIN".equals(actor.role),q,from,to,officerId)));
    }
    @DeleteMapping("/entries/{id}")
    ResponseEntity<Void> deleteEntry(Authentication auth,@PathVariable long id) {
        Account actor=current(auth);
        entryService.delete(id,"ADMIN".equals(actor.role),actor.username,actor.id); return ResponseEntity.noContent().build();
    }

    @GetMapping("/dashboard")
    DashboardSummary dashboard(Authentication auth,@RequestParam(required=false) LocalDate date,
                               @RequestParam(required=false) Long officerId) {
        Account actor=current(auth);
        return entryService.dashboard(actor.username,"ADMIN".equals(actor.role),date,officerId);
    }
    @GetMapping("/summary")
    Summary summary(Authentication auth,@RequestParam(defaultValue="week") String period,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) Long officerId) {
        Account actor=current(auth);
        return entryService.summary(actor.username,"ADMIN".equals(actor.role),period,from,to,officerId);
    }

    @GetMapping(value="/entries/export",produces="text/csv")
    ResponseEntity<byte[]> exportCsv(Authentication auth,@RequestParam(defaultValue="week") String period,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) Long officerId) {
        List<EntryView> rows=exportRows(auth,period,from,to,officerId);
        StringBuilder csv=new StringBuilder("Date,Tracking number,PRN,Amount (UGX),Officer\r\n");
        for(EntryView entry:rows) csv.append(entry.date()).append(',').append(csvCell(entry.trackingNumber())).append(',')
                .append(csvCell(entry.prn())).append(',').append(entry.amount()).append(',')
                .append(csvCell(entry.officer())).append("\r\n");
        return download(csv.toString().getBytes(StandardCharsets.UTF_8),"collections.csv","text/csv");
    }
    @GetMapping(value="/entries/export.xlsx",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ResponseEntity<byte[]> exportXlsx(Authentication auth,@RequestParam(defaultValue="week") String period,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) Long officerId) {
        try(Workbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sheet=workbook.createSheet("Collections");
            String[] headers={"Date","Tracking number","PRN","Amount","Officer"};
            Row header=sheet.createRow(0);
            for(int i=0;i<headers.length;i++) header.createCell(i).setCellValue(headers[i]);
            int row=1;
            for(EntryView entry:exportRows(auth,period,from,to,officerId)) {
                Row cells=sheet.createRow(row++);
                cells.createCell(0).setCellValue(entry.date().toString());
                cells.createCell(1).setCellValue(entry.trackingNumber());
                cells.createCell(2).setCellValue(entry.prn());
                cells.createCell(3).setCellValue(entry.amount());
                cells.createCell(4).setCellValue(entry.officer());
            }
            workbook.write(out);
            return download(out.toByteArray(),"collections.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not create spreadsheet",ex);
        }
    }
    @GetMapping(value="/imports/template",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ResponseEntity<byte[]> importTemplate() {
        try(Workbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sheet=workbook.createSheet("Template");
            String[] headers={"Date","Tracking number","PRN","Amount"};
            Row header=sheet.createRow(0);
            for(int i=0;i<headers.length;i++) header.createCell(i).setCellValue(headers[i]);
            Row example=sheet.createRow(1);
            example.createCell(0).setCellValue(LocalDate.now(KAMPALA).toString());
            example.createCell(1).setCellValue("G240712-2665");
            example.createCell(2).setCellValue("2270005999848");
            example.createCell(3).setCellValue(125000);
            workbook.write(out);
            return download(out.toByteArray(),"collections-template.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not create import template",ex);
        }
    }
    @GetMapping(value="/reports/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> reportPdf(Authentication auth,@RequestParam(defaultValue="week") String period,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) Long officerId) {
        Account actor=current(auth);
        Summary summary=entryService.summary(actor.username,"ADMIN".equals(actor.role),period,from,to,officerId);
        List<EntryView> rows=exportRows(auth,period,from,to,officerId);
        List<Map<String,Object>> reportEntries=rows.stream().map(entry->Map.<String,Object>of(
                "date",entry.date().toString(),"trackingNumber",entry.trackingNumber(),
                "prn",entry.prn(),"amount",entry.amount(),"officer",entry.officer())).toList();
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("title","Collections Performance Report");
        payload.put("period",summary.label());
        payload.put("periodType",period);
        payload.put("start",summary.start());
        payload.put("end",summary.end());
        payload.put("total",summary.amount());
        payload.put("generatedOn",LocalDate.now(KAMPALA).toString());
        payload.put("scope",officerId==null?"All records visible to "+actor.displayName:accounts.findById(officerId)
                .filter(account->"ADMIN".equals(actor.role)&&"OFFICER".equals(account.role))
                .map(account->account.displayName).orElse(actor.displayName));
        payload.put("entries",reportEntries);
        try {
            var response=importClient.post().uri("/reports/pdf").contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().toEntity(byte[].class);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"collections-report.pdf\"")
                    .body(response.getBody());
        } catch(RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Report service rejected the request",ex);
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Report service is unavailable",ex);
        }
    }

    @PostMapping(value="/imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String,Object> importFile(Authentication auth,@RequestPart("file") MultipartFile file,
                                  @RequestParam(required=false) Long officerId) {
        if(file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a non-empty CSV or Excel file");
        Account actor=current(auth);
        Account owner=actor;
        if("ADMIN".equals(actor.role)&&officerId!=null) {
            owner=accounts.findById(officerId).filter(account->"OFFICER".equals(account.role)&&account.enabled)
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an active officer"));
        } else if(!"ADMIN".equals(actor.role)&&officerId!=null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Officers can import only for themselves");
        }
        try {
            var parts=new LinkedMultiValueMap<String,Object>();
            parts.add("file",new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename(){ return Optional.ofNullable(file.getOriginalFilename()).orElse("upload.csv"); }
            });
            Map<?,?> parsed=importClient.post().uri("/imports/validate").contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts).retrieve().body(Map.class);
            if(parsed==null) throw new IllegalStateException("Import service returned an empty response");
            List<?> rows=parsed.get("rows") instanceof List<?> list?list:List.of();
            List<?> skipped=parsed.get("skipped") instanceof List<?> list?list:List.of();
            int imported=0;
            List<Map<String,Object>> duplicateSkips=new ArrayList<>();
            for(Object object:rows) {
                Map<?,?> row=(Map<?,?>)object;
                try {
                    EntryRequest request=new EntryRequest(LocalDate.parse(str(row,"date")),str(row,"trackingNumber"),
                            str(row,"prn"),str(row,"amount"));
                    entryService.create(request,owner.username,actor.id);
                    imported++;
                } catch(DuplicateEntryException ex) {
                    duplicateSkips.add(Map.of("row",row.get("row")==null?0:row.get("row"),
                            "reason",DuplicateEntryAdvice.details(ex,entries)));
                } catch(ResponseStatusException ex) {
                    duplicateSkips.add(Map.of("row",row.get("row")==null?0:row.get("row"),
                            "reason",ex.getReason()==null?"Invalid row":ex.getReason()));
                } catch(java.time.DateTimeException|IllegalArgumentException ex) {
                    duplicateSkips.add(Map.of("row",row.get("row")==null?0:row.get("row"),
                            "reason",ex.getMessage()==null?"Invalid row":ex.getMessage()));
                }
            }
            List<Object> allSkips=new ArrayList<>(skipped); allSkips.addAll(duplicateSkips);
            return Map.of("imported",imported,"skipped",allSkips,
                    "received",parsed.get("received")==null?rows.size():parsed.get("received"));
        } catch(ResponseStatusException ex) {
            throw ex;
        } catch(RestClientResponseException ex) {
            throw new ResponseStatusException(ex.getStatusCode(),"Import validation failed: "+ex.getResponseBodyAsString(),ex);
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Import service failed",ex);
        }
    }

    private List<EntryView> exportRows(Authentication auth,String period,LocalDate from,LocalDate to,Long officerId) {
        Account actor=current(auth);
        Summary summary=entryService.summary(actor.username,"ADMIN".equals(actor.role),period,from,to,officerId);
        List<EntryView> rows=new ArrayList<>();
        for(int page=0;;page++) {
            Page<EntryView> result=entryService.list(actor.username,"ADMIN".equals(actor.role),page,100,null,
                    LocalDate.parse(summary.start()),LocalDate.parse(summary.end()),officerId);
            rows.addAll(result.getContent());
            if(!result.hasNext()) break;
        }
        return rows;
    }
    private Account current(Authentication auth) {
        return accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
    }
    private Map<String,Object> accountView(Account account) {
        return Map.of("id",account.id,"username",account.username,"displayName",account.displayName,
                "role",account.role,"enabled",account.enabled,"mustChangePassword",account.mustChangePassword);
    }
    private String str(Map<?,?> row,String key) {
        Object value=row.get(key);
        if(value==null) throw new IllegalArgumentException("Missing "+key);
        return value.toString();
    }
    private String csvCell(String text) {
        if(text==null) return "";
        String safe=text.replace("\"","\"\"");
        String significant=safe.stripLeading();
        if(!significant.isEmpty()&&"=+-@".indexOf(significant.charAt(0))>=0) safe="'"+safe;
        return "\""+safe+"\"";
    }
    private ResponseEntity<byte[]> download(byte[] bytes,String name,String type) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"").body(bytes);
    }
}

@RestController
class HealthController {
    @GetMapping("/health") Map<String,String> health() { return Map.of("status","ok"); }
}
