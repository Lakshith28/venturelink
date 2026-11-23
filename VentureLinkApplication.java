package com.venturelink;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class VentureLinkApplication {
    public static void main(String[] a) { SpringApplication.run(VentureLinkApplication.class, a); }

    // ---------- Domain ----------
    record User(String id, String name, @JsonIgnore String email, @JsonIgnore String phone, String role, String city,
                String idMasked, String type, List<String> sectors, List<String> stages,
                long minTicket, long maxTicket, int minReturn, String bio) {}
    record Idea(String id, String owner, String title, String sector, String stage, long ask, int ret, String city,
                String pitch, boolean proto, @JsonIgnore String detail) {}
    record Plan(int id, String name, long min, long max, long price, boolean perIdea, String blurb) {}
    record Sub(String inv, int plan, long ts) {}
    record Unlock(String idea, String inv, long fee, long ts) {}
    record Approach(String id, String idea, String from, String to, String msg, long ts) {}
    record Log(String idea, String by, long ts) {}
    record Mentor(String id, String n, String r, String s) {}
    record Match(String id, String idea, int score, List<String> why) {}
    record Reg(String name, String email, String role, String idType, String idNumber, String dob, String city,
               List<String> sectors, List<String> stages, Long minTicket, Long maxTicket, Integer minReturn) {}
    record Verify(String email, String role, String otp) {}
    record Pending(Reg r, String id, String otp, long exp) {}
    record NewIdea(String ownerId, String title, String sector, String stage, long ask, int ret, String pitch, String detail, String proto) {}
    record NewGroup(String ideaId, String investorId, long amount) {}
    record NewTeam(String ownerId, String title, String desc, String skills, int size) {}
    record Msg(String ideaId, String from, String to, String msg) {}

    public static class Group {
        public String id, idea, status; public long ts;
        public List<String> members = new CopyOnWriteArrayList<>();
        public Map<String, Long> amounts = new ConcurrentHashMap<>();
    }
    public static class Team {
        public String id, owner, title, desc, skills; public int size;
        public List<String> members = new CopyOnWriteArrayList<>(), requests = new CopyOnWriteArrayList<>();
    }

    static final List<Plan> PLANS = List.of(
        new Plan(1, "Basic Access", 70000, 10000000, 4999, false, "Full details and direct contact for any idea. Flat monthly fee."),
        new Plan(2, "Small Scale Tier", 70000, 500000, 8000, true, "Low-return, small-scale ideas from ₹70K to ₹5L. ₹8,000 for every idea you invest in."),
        new Plan(3, "Medium Scale Tier", 500001, 2500000, 20000, true, "Medium-return ideas from ₹5L to ₹25L. ₹20,000 for every idea you invest in."),
        new Plan(4, "High Scale Tier", 2500001, 10000000, 50000, true, "High-return ideas from ₹26L to ₹1 Cr. ₹50,000 for every idea you invest in."));
    static int band(long ask) { return ask <= 500000 ? 2 : ask <= 2500000 ? 3 : 4; }
    static final Pattern DATA = Pattern.compile("^data:(image/(?:png|jpeg));base64,(.+)$", Pattern.DOTALL);
    static final Comparator<Match> BY_SCORE = (x, y) -> Integer.compare(y.score(), x.score());

    // ---------- In-memory store (swap for PostgreSQL later) ----------
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final List<Idea> ideas = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> protos = new ConcurrentHashMap<>();
    private final Map<String, String> protoType = new ConcurrentHashMap<>();
    private final List<Sub> subs = new CopyOnWriteArrayList<>();
    private final List<Unlock> unlocks = new CopyOnWriteArrayList<>();
    private final List<Approach> approaches = new CopyOnWriteArrayList<>();
    private final List<Log> interests = new CopyOnWriteArrayList<>();
    private final Set<String> saved = ConcurrentHashMap.newKeySet(), mreq = ConcurrentHashMap.newKeySet();
    private final List<Group> groups = new CopyOnWriteArrayList<>();
    private final List<Team> teams = new CopyOnWriteArrayList<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, String> idLink = new ConcurrentHashMap<>();
    private final List<Mentor> mentors = List.of(
        new Mentor("m1", "Dr. Anita Rao", "Ex-VP, SBI Ventures", "FinTech"),
        new Mentor("m2", "Vikram Sethi", "Founder, 2 exits (AgriTech)", "AgriTech"),
        new Mentor("m3", "Dr. Farah Khan", "Healthcare policy advisor", "HealthTech"),
        new Mentor("m4", "Suresh Iyer", "Scale-up growth coach", "E-commerce"));

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String uid() { return UUID.randomUUID().toString().substring(0, 8); }
    private static long now() { return System.currentTimeMillis(); }

    private User lookup(String me) {
        if (blank(me)) return null;
        User u = users.get(me);
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user");
        return u;
    }
    private User investor(String id) {
        User u = users.get(id);
        if (u == null || !u.role().equals("investor")) throw bad("Unknown investor");
        return u;
    }
    private User entrepreneur(String id) {
        User u = users.get(id);
        if (u == null || !u.role().equals("entrepreneur")) throw bad("Only entrepreneurs can do this");
        return u;
    }
    private Idea find(String id) {
        return ideas.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Idea not found"));
    }
    private boolean hasPlan(String inv, int plan) { return subs.stream().anyMatch(s -> s.inv().equals(inv) && s.plan() == plan); }

    // ---------- Sample data ----------
    private void u(String id, String name, String role, String city, String type, List<String> sec, List<String> stg,
                   long minT, long maxT, int minR, String bio) {
        int h = Math.abs(id.hashCode());
        users.put(id, new User(id, name, id + "@demo.in", "+91 98" + String.format("%08d", h % 100000000), role, city,
                "XXXX" + String.format("%04d", h % 10000), type, sec, stg, minT, maxT, minR, bio));
    }
    private void i(String id, String owner, String title, String sector, String stage, long ask, int ret, String pitch, String detail) {
        ideas.add(new Idea(id, owner, title, sector, stage, ask, ret, users.get(owner).city(), pitch, false, detail));
    }

    @PostConstruct
    void seed() {
        u("u1", "Aarav Mehta", "entrepreneur", "Hyderabad", null, List.of(), List.of(), 0, 0, 0, "Founder of Soil-Sense and PaySathi.");
        u("u2", "Priya Nair", "entrepreneur", "Kochi", null, List.of(), List.of(), 0, 0, 0, "Healthcare and retail SaaS founder.");
        u("u3", "Rohan Das", "entrepreneur", "Kolkata", null, List.of(), List.of(), 0, 0, 0, "Clean-energy and insurance founder.");
        u("u4", "Sneha Reddy", "entrepreneur", "Vijayawada", null, List.of(), List.of(), 0, 0, 0, "EdTech and wearables founder.");
        u("v1", "Kiran Rao", "investor", "Hyderabad", "Angel investor", List.of("AgriTech", "FinTech", "HealthTech"), List.of("Prototype", "Early revenue"), 200000, 3000000, 15, "Ex-banker backing practical tech for rural India.");
        u("v2", "Meera Kapoor", "investor", "Mumbai", "Seed fund", List.of("EdTech", "CleanTech", "E-commerce"), List.of("Idea", "Prototype"), 500000, 5000000, 20, "Seed fund focused on climate and education.");
        u("v3", "Arjun Malhotra", "investor", "Bengaluru", "VC partner", List.of("HealthTech", "FinTech"), List.of("Early revenue"), 2500000, 10000000, 25, "VC partner investing in revenue-stage health and fintech.");
        u("v4", "Neha Joshi", "investor", "Pune", "Micro angel", List.of("EdTech", "E-commerce", "AgriTech"), List.of("Idea", "Prototype"), 70000, 500000, 10, "Small cheques for first-time student founders.");
        i("i1", "u1", "Soil-Sense", "AgriTech", "Prototype", 1500000, 22, "Low-cost IoT soil sensors that send SMS advice to small farmers.\nPilot running on 40 farms in Telangana.", "BOM ₹900/unit. Regional-language SMS advisories. 18% yield uplift measured across the pilot.");
        i("i2", "u1", "PaySathi", "FinTech", "Idea", 800000, 18, "UPI micro-savings app for gig workers.\n600-person waitlist from delivery-partner unions.", "Auto round-up savings swept into partner bank accounts. Revenue from bank referral fees.");
        i("i3", "u2", "MediQueue", "HealthTech", "Early revenue", 4000000, 34, "Appointment and queue SaaS for small clinics.\n22 paying clinics already.", "₹1.1 L MRR, 4% monthly churn. Funds will expand to 3 states.");
        i("i4", "u3", "SolarShare", "CleanTech", "Prototype", 2500000, 24, "Rooftop solar pooling for housing societies.\nPilot approved by 2 societies.", "180 kW combined pilot. Net-metering and billing handled in-app.");
        i("i5", "u4", "LearnLoop", "EdTech", "Idea", 300000, 12, "Peer-teaching marketplace for Class 9–12 students.\n3 schools signed a pilot letter of intent.", "Students earn credits by teaching juniors; schools pay a small annual licence.");
        i("i6", "u2", "KiranaKart", "E-commerce", "Early revenue", 3500000, 30, "B2B ordering app for neighbourhood kirana stores.\n140 stores onboarded.", "₹38 L GMV last quarter, 2 distributor tie-ups, 3% take rate.");
        i("i7", "u3", "CropCover", "FinTech", "Prototype", 2800000, 28, "Crop insurance that pays out automatically from rainfall data.\nReinsurance partner in talks.", "Satellite-rainfall triggers, zero claim paperwork, premiums from state subsidy schemes.");
        i("i8", "u4", "VitalBand", "HealthTech", "Prototype", 5500000, 40, "₹1,800 wearable for rural cardiac monitoring.\nAlerts go to ASHA workers.", "ECG anomaly detection model, clinical validation under way with a district hospital.");
        i("i9", "u2", "ChaiCart", "E-commerce", "Idea", 150000, 9, "Electric chai carts for campuses and offices.\nFirst 3 carts pre-booked.", "Unit cost ₹48K, payback in 11 months, franchise model for students.");
        i("i10", "u1", "StudyBuddy", "EdTech", "Prototype", 450000, 11, "AI study planner in regional languages.\n1,200 beta users.", "Freemium app, ₹99/month premium, 6% conversion in beta.");
        subs.add(new Sub("v1", 3, now() - 5 * 86400000L));
        subs.add(new Sub("v2", 1, now() - 9 * 86400000L));
        unlocks.add(new Unlock("i1", "v1", 20000, now() - 3 * 86400000L));
        unlocks.add(new Unlock("i3", "v2", 0, now() - 2 * 86400000L));
        approaches.add(new Approach(uid(), "i3", "u2", "v3", "We have 22 paying clinics and want a health-focused lead investor.", now() - 86400000L));
        interests.add(new Log("i1", "v1", now() - 3 * 86400000L));
        Group g = new Group(); g.id = "g1"; g.idea = "i1"; g.status = "forming"; g.ts = now();
        g.members.addAll(List.of("v1", "v2")); g.amounts.put("v1", 600000L); g.amounts.put("v2", 500000L); groups.add(g);
        Group h = new Group(); h.id = "g2"; h.idea = "i4"; h.status = "pending"; h.ts = now();
        h.members.addAll(List.of("v2", "v3", "v4")); h.amounts.put("v2", 800000L); h.amounts.put("v3", 1000000L); h.amounts.put("v4", 400000L); groups.add(h);
        Team t = new Team(); t.id = "t1"; t.owner = "u1"; t.title = "AgriFintech Bridge"; t.size = 3;
        t.desc = "Combine soil data with micro-loans so farmers get credit based on their land health.";
        t.skills = "Backend developer, finance / lending expert"; t.members.add("u1"); t.requests.add("u2"); teams.add(t);
        Team t2 = new Team(); t2.id = "t2"; t2.owner = "u3"; t2.title = "Green Mobility Hub"; t2.size = 4;
        t2.desc = "Build a shared EV-charging network for housing societies."; t2.skills = "Hardware engineer, operations lead";
        t2.members.addAll(List.of("u3", "u4")); teams.add(t2);
    }

    // ---------- State ----------
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam(required = false) String me) {
        User u = lookup(me);
        boolean inv = u != null && u.role().equals("investor");
        Set<String> mine = (u != null && !inv)
                ? ideas.stream().filter(x -> x.owner().equals(u.id())).map(Idea::id).collect(Collectors.toSet()) : Set.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", users.values());
        m.put("ideas", ideas);
        m.put("plans", PLANS);
        m.put("subs", inv ? subs.stream().filter(s -> s.inv().equals(u.id())).map(Sub::plan).toList() : List.of());
        m.put("unlocks", u == null ? List.of() : unlocks.stream().filter(x -> inv ? x.inv().equals(u.id()) : mine.contains(x.idea())).toList());
        m.put("approaches", u == null ? List.of() : approaches.stream().filter(a -> inv ? a.to().equals(u.id()) : a.from().equals(u.id())).toList());
        m.put("interests", u == null ? List.of() : interests.stream().filter(l -> inv ? l.by().equals(u.id()) : mine.contains(l.idea())).toList());
        m.put("groups", u == null ? List.of() : groups);
        m.put("teams", u == null ? List.of() : teams);
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

    // ---------- Login: Aadhaar/PAN + email linked to the ID + OTP ----------
    @PostMapping("/auth/start")
    public Map<String, Object> start(@RequestBody Reg r) {
        String role = String.valueOf(r.role());
        if (!role.equals("investor") && !role.equals("entrepreneur")) throw bad("Choose a valid role");
        if (blank(r.name())) throw bad("Name is required");
        if (r.email() == null || !r.email().trim().matches("^\\S+@\\S+\\.\\S+$")) throw bad("Enter a valid email");
        String email = r.email().trim().toLowerCase();
        String id = r.idNumber() == null ? "" : r.idNumber().replaceAll("[\\s-]", "").toUpperCase();
        boolean ok = "Aadhaar".equalsIgnoreCase(r.idType()) ? id.matches("\\d{12}")
                : "PAN".equalsIgnoreCase(r.idType()) && id.matches("[A-Z]{5}\\d{4}[A-Z]");
        if (!ok) throw bad("Enter a valid Aadhaar (12 digits) or PAN (ABCDE1234F)");
        LocalDate dob;
        try { dob = LocalDate.parse(r.dob()); } catch (Exception e) { throw bad("Select your date of birth"); }
        if (Period.between(dob, LocalDate.now()).getYears() < 18) throw bad("You must be 18 or older");
        String linked = idLink.get(sha(id));
        if (linked != null && !linked.equals(email)) throw bad("This ID is already linked to a different email");
        boolean exists = users.values().stream().anyMatch(x -> x.email().equalsIgnoreCase(email) && x.role().equals(role));
        if (!exists && role.equals("investor") && (r.sectors() == null || r.sectors().isEmpty() || r.stages() == null || r.stages().isEmpty()))
            throw bad("Pick at least one field and stage");
        String otp = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        pending.put(email + "|" + role, new Pending(r, id, otp, now() + 600_000));
        return Map.of("otp", otp, "note", "Demo mode: in production this code is emailed to you");
    }

    @PostMapping("/auth/verify")
    public User verify(@RequestBody Verify v) {
        String key = String.valueOf(v.email()).trim().toLowerCase() + "|" + v.role();
        Pending p = pending.get(key);
        if (p == null || p.exp() < now() || !p.otp().equals(String.valueOf(v.otp()).trim())) throw bad("Invalid or expired OTP");
        pending.remove(key);
        String email = key.substring(0, key.indexOf('|'));
        Optional<User> ex = users.values().stream().filter(x -> x.email().equalsIgnoreCase(email) && x.role().equals(v.role())).findFirst();
        if (ex.isPresent()) { idLink.putIfAbsent(sha(p.id()), email); return ex.get(); }
        Reg r = p.r();
        boolean inv = v.role().equals("investor");
        User u = new User("x" + uid(), r.name().trim(), email, "+91 90000 00000", v.role(), blank(r.city()) ? "India" : r.city().trim(),
                "XXXX" + p.id().substring(p.id().length() - 4), inv ? "Investor" : null,
                inv ? r.sectors() : List.of(), inv ? r.stages() : List.of(),
                inv && r.minTicket() != null ? r.minTicket() : 70000, inv && r.maxTicket() != null ? r.maxTicket() : 10000000,
                inv && r.minReturn() != null ? r.minReturn() : 10, "New to Venture Link");
        users.put(u.id(), u);
        idLink.put(sha(p.id()), email);
        return u;
    }

    // ---------- Ideas ----------
    @PostMapping("/ideas")
    public Idea addIdea(@RequestBody NewIdea r) {
        User o = entrepreneur(r.ownerId());
        if (blank(r.title()) || blank(r.sector()) || blank(r.stage()) || blank(r.pitch()) || blank(r.detail())) throw bad("Fill all fields");
        if (r.pitch().length() > 300) throw bad("Keep the summary to 2–3 lines (300 characters)");
        if (r.ask() < 70000 || r.ask() > 10_000_000) throw bad("Required investment must be between ₹70,000 and ₹1 crore");
        if (r.ret() < 1 || r.ret() > 100) throw bad("Estimated return must be between 1% and 100%");
        byte[] b = null; String ct = null;
        if (!blank(r.proto())) {
            Matcher m = DATA.matcher(r.proto());
            if (!m.matches()) throw bad("Prototype must be a PNG or JPEG image");
            try { b = Base64.getDecoder().decode(m.group(2)); } catch (IllegalArgumentException e) { throw bad("Bad image data"); }
            if (b.length > 1_500_000) throw bad("Image too large (max 1.5 MB)");
            ct = m.group(1);
        }
        String id = "i" + uid();
        Idea i = new Idea(id, o.id(), r.title().trim(), r.sector(), r.stage(), r.ask(), r.ret(), o.city(), r.pitch().trim(), b != null, r.detail().trim());
        if (b != null) { protos.put(id, b); protoType.put(id, ct); }
        ideas.add(i);
        return i;
    }

    @GetMapping("/ideas/{id}/proto")
    public ResponseEntity<byte[]> proto(@PathVariable String id) {
        byte[] b = protos.get(id);
        if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(protoType.get(id)))
                .header("Cache-Control", "public, max-age=3600").body(b);
    }

    // ---------- Subscriptions and unlocking ----------
    @PostMapping("/subscribe")
    public Sub subscribe(@RequestParam String investorId, @RequestParam int plan) {
        investor(investorId);
        if (plan < 1 || plan > 4) throw bad("Unknown plan");
        if (hasPlan(investorId, plan)) throw bad("You already have this plan");
        Sub s = new Sub(investorId, plan, now());
        subs.add(s);
        return s;
    }

    private Map<String, Object> full(Idea i, long fee) {
        User o = users.get(i.owner());
        return Map.of("detail", i.detail(), "fee", fee, "contact", Map.of("name", o.name(), "email", o.email(), "phone", o.phone()));
    }

    @PostMapping("/ideas/{id}/unlock")
    public Map<String, Object> unlock(@PathVariable String id, @RequestParam String investorId) {
        investor(investorId);
        Idea i = find(id);
        boolean done = unlocks.stream().anyMatch(x -> x.idea().equals(id) && x.inv().equals(investorId));
        long fee = 0;
        if (!done) {
            int b = band(i.ask());
            boolean pass = hasPlan(investorId, 1);
            if (!pass && !hasPlan(investorId, b))
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Subscribe to the " + PLANS.get(b - 1).name() + " (or the Basic Access) to unlock this idea");
            fee = pass ? 0 : PLANS.get(b - 1).price();
            unlocks.add(new Unlock(id, investorId, fee, now()));
        }
        return full(i, fee);
    }

    @GetMapping("/ideas/{id}/full")
    public Map<String, Object> fullView(@PathVariable String id, @RequestParam String investorId) {
        Idea i = find(id);
        Optional<Unlock> u = unlocks.stream().filter(x -> x.idea().equals(id) && x.inv().equals(investorId)).findFirst();
        if (u.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unlock this idea first");
        return full(i, u.get().fee());
    }

    @PostMapping("/ideas/{id}/interest")
    public void interest(@PathVariable String id, @RequestParam String investorId) {
        find(id); investor(investorId);
        if (interests.stream().noneMatch(l -> l.idea().equals(id) && l.by().equals(investorId))) interests.add(new Log(id, investorId, now()));
    }

    @PostMapping("/ideas/{id}/save")
    public void save(@PathVariable String id, @RequestParam String investorId) {
        find(id);
        if (!users.containsKey(investorId)) throw bad("Unknown user");
        String k = investorId + ":" + id;
        if (!saved.add(k)) saved.remove(k);
    }

    // ---------- Entrepreneur approaches investor ----------
    @PostMapping("/approach")
    public Approach approach(@RequestBody Msg m) {
        User o = entrepreneur(m.from());
        investor(m.to());
        Idea i = find(m.ideaId());
        if (!i.owner().equals(o.id())) throw bad("You can only pitch your own ideas");
        if (blank(m.msg())) throw bad("Write a short message");
        Approach a = new Approach(uid(), i.id(), o.id(), m.to(), m.msg().trim(), now());
        approaches.add(a);
        return a;
    }

    // ---------- Combine & Invest ----------
    private Group group(String id) {
        return groups.stream().filter(g -> g.id.equals(id)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
    }
    @PostMapping("/groups")
    public Group newGroup(@RequestBody NewGroup r) {
        investor(r.investorId()); find(r.ideaId());
        if (r.amount() <= 0) throw bad("Enter an amount");
        Group g = new Group(); g.id = "g" + uid(); g.idea = r.ideaId(); g.status = "forming"; g.ts = now();
        g.members.add(r.investorId()); g.amounts.put(r.investorId(), r.amount());
        groups.add(g);
        return g;
    }
    @PostMapping("/groups/{id}/join")
    public Group join(@PathVariable String id, @RequestParam String investorId, @RequestParam long amount) {
        investor(investorId);
        Group g = group(id);
        if (!g.status.equals("forming")) throw bad("This group is closed");
        if (g.members.contains(investorId)) throw bad("You are already in this group");
        if (g.members.size() >= 4) throw bad("This group is full (max 4 investors)");
        if (amount <= 0) throw bad("Enter an amount");
        g.members.add(investorId); g.amounts.put(investorId, amount);
        return g;
    }
    @PostMapping("/groups/{id}/submit")
    public Group submit(@PathVariable String id, @RequestParam String investorId) {
        Group g = group(id);
        if (!g.members.contains(investorId)) throw bad("Only group members can submit");
        if (!g.status.equals("forming")) throw bad("Already submitted");
        if (g.members.size() < 3) throw bad("A group needs 3–4 investors before it can be submitted");
        g.status = "pending";
        return g;
    }
    @PostMapping("/groups/{id}/decision")
    public Group decide(@PathVariable String id, @RequestParam String ownerId, @RequestParam boolean accept) {
        Group g = group(id);
        if (!find(g.idea).owner().equals(ownerId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the idea owner can decide");
        if (!g.status.equals("pending")) throw bad("Nothing to decide");
        g.status = accept ? "accepted" : "declined";
        return g;
    }

    // ---------- Combine & Grow (entrepreneur teams) ----------
    private Team team(String id) {
        return teams.stream().filter(t -> t.id.equals(id)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
    }
    @PostMapping("/teams")
    public Team newTeam(@RequestBody NewTeam r) {
        User o = entrepreneur(r.ownerId());
        if (blank(r.title()) || blank(r.desc()) || blank(r.skills())) throw bad("Fill all fields");
        if (r.size() < 2 || r.size() > 5) throw bad("Team size must be 2–5");
        Team t = new Team(); t.id = "t" + uid(); t.owner = o.id(); t.title = r.title().trim(); t.desc = r.desc().trim(); t.skills = r.skills().trim(); t.size = r.size();
        t.members.add(o.id());
        teams.add(t);
        return t;
    }
    @PostMapping("/teams/{id}/join")
    public Team joinTeam(@PathVariable String id, @RequestParam String userId) {
        entrepreneur(userId);
        Team t = team(id);
        if (t.members.contains(userId) || t.requests.contains(userId)) throw bad("You already requested or joined");
        if (t.members.size() >= t.size) throw bad("This team is full");
        t.requests.add(userId);
        return t;
    }
    @PostMapping("/teams/{id}/accept")
    public Team accept(@PathVariable String id, @RequestParam String ownerId, @RequestParam String userId) {
        Team t = team(id);
        if (!t.owner.equals(ownerId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the team lead can accept");
        if (!t.requests.remove(userId)) throw bad("No such request");
        if (t.members.size() >= t.size) throw bad("This team is full");
        t.members.add(userId);
        return t;
    }

    // ---------- AI matching ----------
    private Match scoreIdea(Idea i, User inv, String id) {
        int s = 0; List<String> w = new ArrayList<>();
        if (inv.sectors().contains(i.sector())) { s += 40; w.add("Field fit: " + i.sector()); }
        if (inv.stages().contains(i.stage())) { s += 15; w.add("Likes " + i.stage() + " stage"); }
        if (i.ask() >= inv.minTicket() && i.ask() <= inv.maxTicket()) { s += 30; w.add("Ask is within ticket range"); }
        if (i.ret() >= inv.minReturn()) { s += 15; w.add("Est. return " + i.ret() + "% meets target"); }
        return new Match(id, i.id(), s, w);
    }
    @GetMapping("/match")
    public List<Match> match(@RequestParam String userId) {
        User u = users.get(userId);
        if (u == null) throw bad("Unknown user");
        if (u.role().equals("investor"))
            return ideas.stream().map(i -> scoreIdea(i, u, i.id())).sorted(BY_SCORE).toList();
        List<Idea> mine = ideas.stream().filter(i -> i.owner().equals(u.id())).toList();
        return users.values().stream().filter(x -> x.role().equals("investor"))
                .map(inv -> mine.stream().map(i -> scoreIdea(i, inv, inv.id())).max(Comparator.comparingInt(Match::score))
                        .orElse(new Match(inv.id(), "", 0, List.of())))
                .sorted(BY_SCORE).toList();
    }

    private static String sha(String s) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------- Profiles: public view for everyone, private details only for the owner ----------
    @GetMapping("/profile/{id}")
    public Map<String, Object> profile(@PathVariable String id, @RequestParam(required = false) String viewer) {
        User u = users.get(id);
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
        boolean self = id.equals(viewer), inv = u.role().equals("investor");
        Set<String> mine = ideas.stream().filter(x -> x.owner().equals(id)).map(Idea::id).collect(Collectors.toSet());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id()); m.put("name", u.name()); m.put("role", u.role()); m.put("city", u.city());
        m.put("type", u.type()); m.put("bio", u.bio()); m.put("verified", true); m.put("self", self);
        m.put("sectors", u.sectors()); m.put("stages", u.stages());
        m.put("minTicket", u.minTicket()); m.put("maxTicket", u.maxTicket()); m.put("minReturn", u.minReturn());
        m.put("ideas", mine.size());
        m.put("unlocks", (int) unlocks.stream().filter(x -> inv ? x.inv().equals(id) : mine.contains(x.idea())).count());
        m.put("groups", (int) groups.stream().filter(g -> g.members.contains(id)).count());
        m.put("approaches", (int) approaches.stream().filter(a -> inv ? a.to().equals(id) : a.from().equals(id)).count());
        boolean unlocked = !inv && viewer != null && unlocks.stream().anyMatch(x -> x.inv().equals(viewer) && mine.contains(x.idea()));
        if (self || unlocked) { m.put("email", u.email()); m.put("phone", u.phone()); }
        if (self) {
            m.put("idMasked", u.idMasked());
            m.put("plans", subs.stream().filter(s -> s.inv().equals(id)).map(s -> PLANS.get(s.plan() - 1).name()).toList());
        }
        return m;
    }

    // ---------- Mentors ----------
    @PostMapping("/mentors/{id}/request")
    public void mentor(@PathVariable String id, @RequestParam String userId) {
        if (!users.containsKey(userId)) throw bad("Unknown user");
        if (mentors.stream().noneMatch(m -> m.id().equals(id))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mentor not found");
        mreq.add(userId + ":" + id);
    }
}
