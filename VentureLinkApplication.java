package com.venturelink;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class VentureLinkApplication {
    public static void main(String[] a) { SpringApplication.run(VentureLinkApplication.class, a); }

    // ---- Domain (records). @JsonIgnore keeps email and confidential details out of API output ----
    record User(String id, String name, @JsonIgnore String email, String role, String city, String idMasked,
                String type, List<String> sectors, List<String> stages, long maxTicket) {}
    record Idea(String id, String owner, String title, String sector, String stage, long ask, String city,
                String pitch, @JsonIgnore String detail) {}
    record Log(String idea, String by, long ts) {}
    record Mentor(String id, String n, String r, String s) {}
    record Reg(String name, String email, String role, String idType, String idNumber, String dob,
               List<String> sectors, List<String> stages, Long maxTicket) {}
    record NewIdea(String ownerId, String title, String sector, String stage, long ask, String pitch, String detail) {}

    // ---- In-memory store (replace with JPA + PostgreSQL for permanent data) ----
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final List<Idea> ideas = new CopyOnWriteArrayList<>();
    private final List<Log> views = new CopyOnWriteArrayList<>(), interests = new CopyOnWriteArrayList<>();
    private final Set<String> saved = ConcurrentHashMap.newKeySet(), mreq = ConcurrentHashMap.newKeySet();
    private final List<Mentor> mentors = List.of(
        new Mentor("m1", "Dr. Anita Rao", "Ex-VP, SBI Ventures", "FinTech"),
        new Mentor("m2", "Vikram Sethi", "Founder, 2 exits (AgriTech)", "AgriTech"),
        new Mentor("m3", "Dr. Farah Khan", "Healthcare policy advisor", "HealthTech"),
        new Mentor("m4", "Suresh Iyer", "Scale-up growth coach", "E-commerce"));

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }

    // ---- Sample data ----
    private void u(String id, String name, String role, String city, String type, List<String> sec, List<String> stg, long max) {
        users.put(id, new User(id, name, id + "@demo.in", role, city, "XXXX" + String.format("%04d", Math.abs(id.hashCode()) % 10000), type, sec, stg, max));
    }
    private void i(String id, String owner, String title, String sector, String stage, long ask, String city, String pitch, String detail) {
        ideas.add(new Idea(id, owner, title, sector, stage, ask, city, pitch, detail));
    }
    private void v(String idea, String by, double daysAgo) { views.add(new Log(idea, by, System.currentTimeMillis() - (long) (daysAgo * 86400000L))); }

    @PostConstruct
    void seed() {
        u("u1", "Aarav Mehta", "entrepreneur", "Hyderabad", null, List.of(), List.of(), 0);
        u("u2", "Priya Nair", "entrepreneur", "Kochi", null, List.of(), List.of(), 0);
        u("u3", "Rohan Das", "entrepreneur", "Kolkata", null, List.of(), List.of(), 0);
        u("u4", "Sneha Reddy", "entrepreneur", "Vijayawada", null, List.of(), List.of(), 0);
        u("v1", "Kiran Rao", "investor", "Hyderabad", "Angel investor", List.of("AgriTech", "FinTech", "HealthTech"), List.of("Prototype", "Early revenue"), 3000000);
        u("v2", "Meera Kapoor", "investor", "Mumbai", "Seed fund", List.of("EdTech", "CleanTech", "E-commerce"), List.of("Idea", "Prototype"), 5000000);
        u("v3", "Arjun Malhotra", "investor", "Bengaluru", "VC partner", List.of("HealthTech", "FinTech"), List.of("Early revenue"), 10000000);
        i("i1", "u1", "Soil-Sense", "AgriTech", "Prototype", 1500000, "Hyderabad", "Low-cost IoT soil sensors with SMS alerts for small farms.", "BOM ₹900/unit. Regional-language SMS advisories. Pilot on 40 farms in Telangana, 18% yield uplift measured.");
        i("i2", "u1", "PaySathi", "FinTech", "Idea", 800000, "Hyderabad", "UPI micro-savings for gig workers.", "Auto round-up savings swept into partner bank accounts. 600-person waitlist from delivery-partner unions.");
        i("i3", "u2", "MediQueue", "HealthTech", "Early revenue", 4000000, "Kochi", "Appointment and queue SaaS for small clinics.", "22 paying clinics, ₹1.1 L MRR, 4% monthly churn. Seeking funds for 3-state expansion.");
        i("i4", "u3", "SolarShare", "CleanTech", "Prototype", 2500000, "Kolkata", "Rooftop solar pooling for housing societies.", "Pilot approved by 2 societies, 180 kW combined. Net-metering handled in-app.");
        i("i5", "u4", "LearnLoop", "EdTech", "Idea", 600000, "Vijayawada", "Peer-teaching marketplace for Class 9-12 students.", "Students earn credits by teaching juniors. 3 schools signed a pilot LOI.");
        i("i6", "u2", "KiranaKart", "E-commerce", "Early revenue", 3500000, "Kochi", "B2B ordering app for neighbourhood kirana stores.", "140 stores onboarded, ₹38 L GMV last quarter, 2 distributor tie-ups.");
        i("i7", "u3", "CropCover", "FinTech", "Prototype", 2800000, "Kolkata", "Parametric crop insurance paid out automatically on rainfall data.", "Satellite-rainfall triggers, zero claim paperwork. Reinsurance partner in talks.");
        i("i8", "u4", "VitalBand", "HealthTech", "Prototype", 5500000, "Vijayawada", "Affordable wearable for rural cardiac monitoring.", "₹1,800 device, ECG anomaly alerts to ASHA workers. Clinical validation under way.");
        v("i1", "v1", 2); v("i1", "v3", 5); v("i2", "v1", 1); v("i3", "v1", 3); v("i3", "v3", 1);
        v("i3", "v2", 6); v("i4", "v2", 2); v("i6", "v2", 4); v("i7", "v1", 1); v("i8", "v3", 2);
        interests.add(new Log("i1", "v1", 0)); interests.add(new Log("i3", "v3", 0)); interests.add(new Log("i4", "v2", 0));
    }

    // ---- API ----
    /** One call returns everything the signed-in user may see. Confidential detail is never included. */
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam(required = false) String me) {
        User u = (me == null || me.isBlank()) ? null : users.get(me);
        if (me != null && !me.isBlank() && u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user");
        Set<String> mine = u == null ? Set.of() : ideas.stream().filter(x -> x.owner().equals(u.id())).map(Idea::id).collect(Collectors.toSet());
        Predicate<Log> visible = l -> u != null && (u.role().equals("investor") ? l.by().equals(u.id()) : mine.contains(l.idea()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", users.values());
        m.put("ideas", ideas);
        m.put("views", views.stream().filter(visible).toList());
        m.put("interests", interests.stream().filter(visible).toList());
        m.put("saved", own(saved, u));
        m.put("mreq", own(mreq, u));
        m.put("mentors", mentors);
        return m;
    }
    private List<String> own(Set<String> s, User u) {
        if (u == null) return List.of();
        String p = u.id() + ":";
        return s.stream().filter(k -> k.startsWith(p)).map(k -> k.substring(p.length())).toList();
    }

    @PostMapping("/register")
    public User register(@RequestBody Reg r) {
        String role = String.valueOf(r.role());
        if (!role.equals("investor") && !role.equals("entrepreneur")) throw bad("Invalid role");
        if (r.name() == null || r.name().isBlank()) throw bad("Name is required");
        if (r.email() == null || !r.email().matches("^\\S+@\\S+\\.\\S+$")) throw bad("Invalid email");
        String id = r.idNumber() == null ? "" : r.idNumber().replaceAll("[\\s-]", "").toUpperCase();
        boolean ok = "Aadhaar".equalsIgnoreCase(r.idType()) ? id.matches("\\d{12}")
                   : "PAN".equalsIgnoreCase(r.idType()) && id.matches("[A-Z]{5}\\d{4}[A-Z]");
        if (!ok) throw bad("Invalid government ID");
        LocalDate dob;
        try { dob = LocalDate.parse(r.dob()); } catch (Exception e) { throw bad("Invalid date of birth"); }
        if (Period.between(dob, LocalDate.now()).getYears() < 18) throw bad("You must be 18 or older");
        Optional<User> existing = users.values().stream()
                .filter(x -> x.email().equalsIgnoreCase(r.email().trim()) && x.role().equals(role)).findFirst();
        if (existing.isPresent()) return existing.get();
        boolean inv = role.equals("investor");
        if (inv && (r.sectors() == null || r.sectors().isEmpty() || r.stages() == null || r.stages().isEmpty()))
            throw bad("Pick at least one sector and stage");
        User u = new User("x" + UUID.randomUUID().toString().substring(0, 8), r.name().trim(), r.email().trim(), role, "India",
                "XXXX" + id.substring(id.length() - 4), inv ? "Investor" : null,
                inv ? r.sectors() : List.of(), inv ? r.stages() : List.of(), inv && r.maxTicket() != null ? r.maxTicket() : 0);
        users.put(u.id(), u);
        return u;
    }

    @PostMapping("/ideas")
    public Idea addIdea(@RequestBody NewIdea r) {
        User o = users.get(r.ownerId());
        if (o == null || !o.role().equals("entrepreneur")) throw bad("Only entrepreneurs can pitch");
        if (blank(r.title()) || blank(r.sector()) || blank(r.stage()) || blank(r.pitch()) || blank(r.detail()) || r.ask() <= 0)
            throw bad("Fill all fields");
        Idea i = new Idea("i" + UUID.randomUUID().toString().substring(0, 8), o.id(), r.title().trim(), r.sector(), r.stage(),
                r.ask(), o.city(), r.pitch().trim(), r.detail().trim());
        ideas.add(i);
        return i;
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private Idea find(String id) {
        return ideas.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Idea not found"));
    }

    /** Reveals confidential detail only after NDA acceptance, and writes a timestamped access record. */
    @PostMapping("/ideas/{id}/view")
    public Map<String, Object> view(@PathVariable String id, @RequestParam String investorId, @RequestParam boolean ndaAccepted) {
        if (!ndaAccepted) throw bad("NDA must be accepted");
        User inv = users.get(investorId);
        if (inv == null || !inv.role().equals("investor")) throw bad("Unknown investor");
        Idea i = find(id);
        long ts = System.currentTimeMillis();
        views.add(new Log(id, inv.id(), ts));
        return Map.of("detail", i.detail(), "ts", ts);
    }

    @PostMapping("/ideas/{id}/interest")
    public void interest(@PathVariable String id, @RequestParam String investorId) {
        find(id);
        User inv = users.get(investorId);
        if (inv == null || !inv.role().equals("investor")) throw bad("Unknown investor");
        if (interests.stream().noneMatch(l -> l.idea().equals(id) && l.by().equals(investorId)))
            interests.add(new Log(id, investorId, System.currentTimeMillis()));
    }

    @PostMapping("/ideas/{id}/save")
    public void save(@PathVariable String id, @RequestParam String investorId) {
        find(id);
        if (!users.containsKey(investorId)) throw bad("Unknown user");
        String k = investorId + ":" + id;
        if (!saved.add(k)) saved.remove(k);
    }

    @PostMapping("/mentors/{id}/request")
    public void mentor(@PathVariable String id, @RequestParam String userId) {
        if (!users.containsKey(userId)) throw bad("Unknown user");
        if (mentors.stream().noneMatch(m -> m.id().equals(id))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mentor not found");
        mreq.add(userId + ":" + id);
    }
}
