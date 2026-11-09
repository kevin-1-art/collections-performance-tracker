package com.example.collections;

import jakarta.validation.Valid;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.LinkedMultiValueMap;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
class ApiController {
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final EntryService entryService;
    private final EntryRepository entries;
    private final RestClient importClient;
    ApiController(AccountService accountService, AccountRepository accounts, EntryService entryService,
                  EntryRepository entries, @Value("${app.import-service-url}") String importUrl) {
        this.accountService=accountService; this.accounts=accounts; this.entryService=entryService; this.entries=entries;
        this.importClient=RestClient.builder().baseUrl(importUrl).build();
    }

    @PostMapping("/auth/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) { return accountService.login(request); }
    @PostMapping("/auth/password")
    ResponseEntity<Void> changePassword(Authentication auth, @Valid @RequestBody PasswordRequest request) {
        accountService.changePassword(auth.getName(),request); return ResponseEntity.noContent().build();
    }
    @GetMapping("/auth/me")
    Map<String,Object> me(Authentication auth) {
        Account a=accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
        return Map.of("username",a.username,"displayName",a.displayName,"role",a.role,"mustChangePassword",a.mustChangePassword);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/accounts")
    ResponseEntity<Map<String,Object>> createAccount(Authentication auth, @Valid @RequestBody AccountRequest request) {
        Account actor=accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
        Account created=accountService.create(request,actor.id);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",created.id,"username",created.username,"displayName",created.displayName,"role",created.role,"mustChangePassword",true));
    }
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/accounts")
    List<Map<String,Object>> listAccounts() {
        return accounts.findAll(Sort.by("username")).stream().map(a->Map.<String,Object>of("id",a.id,"username",a.username,
                "displayName",a.displayName,"role",a.role,"enabled",a.enabled,"mustChangePassword",a.mustChangePassword)).toList();
    }
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/accounts/{id}/enabled")
    ResponseEntity<Void> enableAccount(Authentication auth,@PathVariable long id,@RequestBody Map<String,Boolean> body) {
        Account actor=accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
        if(actor.id.equals(id)&&Boolean.FALSE.equals(body.get("enabled"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cannot disable your own account");
        accountService.setEnabled(id,Boolean.TRUE.equals(body.get("enabled")),actor.id); return ResponseEntity.noContent().build();
    }

    @PostMapping("/entries")
    ResponseEntity<EntryView> createEntry(Authentication auth,@Valid @RequestBody EntryRequest request) {
        Account actor=accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(entryService.create(request,auth.getName(),actor.id));
    }
    @GetMapping("/entries")
    Page<EntryView> listEntries(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,
            @RequestParam(required=false) String q,@RequestParam(required=false) String tracking,@RequestParam(required=false) String prn,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) {
        Account a=accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
        return entryService.list(auth.getName(),"ADMIN".equals(a.role),page,size,q,tracking,prn,from,to);
    }
    @DeleteMapping("/entries/{id}")
    ResponseEntity<Void> deleteEntry(Authentication auth,@PathVariable long id) {
        Account a=accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
        entryService.delete(id,"ADMIN".equals(a.role),auth.getName(),a.id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/summary")
    Summary summary(Authentication auth,@RequestParam(defaultValue="week") String period,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        Account a=accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
        return entryService.summary(auth.getName(),"ADMIN".equals(a.role),period,from,to);
    }

    @GetMapping(value="/entries/export", produces="text/csv")
    ResponseEntity<byte[]> exportCsv(Authentication auth,@RequestParam(defaultValue="week") String period,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        List<EntryView> rows=exportRows(auth,period,from,to);
        StringBuilder csv=new StringBuilder("ID,Client,Tracking Number,PRN,Amount,Collected At,Receipt,Notes,Officer\r\n");
        for(EntryView e:rows) csv.append(e.id()).append(',').append(csvCell(e.clientName())).append(',').append(csvCell(e.trackingNumber()))
                .append(',').append(csvCell(e.prn())).append(',').append(e.amount()).append(',').append(e.collectedAt()).append(',')
                .append(csvCell(e.receiptNumber())).append(',').append(csvCell(e.notes())).append(',').append(csvCell(e.createdBy())).append("\r\n");
        return download(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"collections.csv","text/csv");
    }
    @GetMapping(value="/entries/export.xlsx", produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ResponseEntity<byte[]> exportXlsx(Authentication auth,@RequestParam(defaultValue="week") String period,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        try(Workbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sheet=wb.createSheet("Collections"); String[] headers={"ID","Client","Tracking Number","PRN","Amount","Collected At","Receipt","Notes","Officer"};
            Row header=sheet.createRow(0); for(int i=0;i<headers.length;i++) header.createCell(i).setCellValue(headers[i]);
            int row=1; for(EntryView e:exportRows(auth,period,from,to)) {
                Row r=sheet.createRow(row++); Object[] values={e.id(),e.clientName(),e.trackingNumber(),e.prn(),e.amount().doubleValue(),e.collectedAt().toString(),e.receiptNumber(),e.notes(),e.createdBy()};
                for(int i=0;i<values.length;i++) if(values[i]!=null) {
                    Cell cell=r.createCell(i);
                    if(values[i] instanceof Number number) cell.setCellValue(number.doubleValue());
                    else cell.setCellValue(values[i].toString());
                }
            }
            wb.write(out); return download(out.toByteArray(),"collections.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch(Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not create spreadsheet"); }
    }
    @GetMapping(value="/reports/pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> reportPdf(Authentication auth,@RequestParam(defaultValue="week") String period,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        Summary summary=entryService.summary(auth.getName(),isAdmin(auth),period,from,to);
        List<Map<String,Object>> reportEntries=exportRows(auth,period,from,to).stream().map(e->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("clientName",e.clientName()); row.put("trackingNumber",e.trackingNumber()); row.put("prn",e.prn());
            row.put("amount",e.amount()); row.put("collectedAt",e.collectedAt().toString()); row.put("createdBy",e.createdBy());
            return row;
        }).toList();
        Map<String,Object> payload=Map.of("title","Collections Performance Report","period",summary.label(),
                "start",summary.start(),"end",summary.end(),"total",summary.amount(),"entries",reportEntries);
        try {
            var response=importClient.post().uri("/reports/pdf").contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().toEntity(byte[].class);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"collections-report.pdf\"").body(response.getBody());
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Report service failed");
        }
    }
    private List<EntryView> exportRows(Authentication auth,String period,LocalDate from,LocalDate to) {
        Summary summary=entryService.summary(auth.getName(),isAdmin(auth),period,from,to);
        Instant start=Instant.parse(summary.start()),end=Instant.parse(summary.end()); List<EntryView> rows=new ArrayList<>();
        for(int page=0;;page++) {
            Page<EntryView> result=entryService.list(auth.getName(),isAdmin(auth),page,100,null,null,null,start,end);
            rows.addAll(result.getContent()); if(!result.hasNext())break;
        }
        return rows;
    }
    private boolean isAdmin(Authentication auth) { return accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow().role.equals("ADMIN"); }
    private String csvCell(String text) {
        if(text==null)return "";
        String safe=text.replace("\"","\"\"");
        String significant=safe.stripLeading();
        if(!significant.isEmpty() && "=+-@".indexOf(significant.charAt(0))>=0) safe="'"+safe;
        return "\""+safe+"\"";
    }
    private ResponseEntity<byte[]> download(byte[] bytes,String name,String type) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"").body(bytes);
    }

    @PostMapping(value="/imports", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String,Object> importFile(Authentication auth,@RequestPart("file") MultipartFile file) {
        if(file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a non-empty CSV or Excel file");
        try {
            var parts=new LinkedMultiValueMap<String,Object>();
            parts.add("file",new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename(){ return Optional.ofNullable(file.getOriginalFilename()).orElse("upload.csv"); }
            });
            Map<?,?> parsed=importClient.post().uri("/imports/validate")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve().body(Map.class);
            if(parsed==null) throw new IllegalStateException("Empty response");
            List<?> rows=parsed.get("rows") instanceof List<?> list?list:List.of();
            List<?> skipped=parsed.get("skipped") instanceof List<?> list?list:List.of();
            Account actor=accounts.findByUsernameIgnoreCase(auth.getName()).orElseThrow();
            int imported=0; List<Map<String,Object>> duplicateSkips=new ArrayList<>();
            for(Object obj:rows) {
                Map<?,?> row=(Map<?,?>)obj;
                try {
                    EntryRequest request=new EntryRequest(str(row,"clientName"),str(row,"trackingNumber"),str(row,"prn"),
                            new BigDecimal(str(row,"amount")),Instant.parse(str(row,"collectedAt")),strOrNull(row,"receiptNumber"),strOrNull(row,"notes"));
                    entryService.create(request,auth.getName(),actor.id); imported++;
                } catch(DuplicateEntryException ex) {
                    duplicateSkips.add(Map.of("row",row.get("row")==null?0:row.get("row"),"reason",DuplicateEntryAdvice.details(ex,entries)));
                } catch(RuntimeException ex) { duplicateSkips.add(Map.of("row",row.get("row")==null?0:row.get("row"),"reason",ex.getMessage()==null?"Invalid row":ex.getMessage())); }
            }
            List<Object> allSkips=new ArrayList<>(skipped); allSkips.addAll(duplicateSkips);
            return Map.of("imported",imported,"skipped",allSkips,"received",parsed.get("received")==null?rows.size():parsed.get("received"));
        } catch(ResponseStatusException e) { throw e; }
        catch(RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(),"Import validation failed: "+e.getResponseBodyAsString());
        }
        catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Import service failed: "+e.getMessage()); }
    }
    private String str(Map<?,?> row,String key) { Object v=row.get(key); if(v==null)throw new IllegalArgumentException("Missing "+key); return v.toString(); }
    private String strOrNull(Map<?,?> row,String key) { Object v=row.get(key); return v==null?null:v.toString(); }
}

@RestController
class HealthController {
    @GetMapping("/health") Map<String,String> health() { return Map.of("status","ok"); }
}
