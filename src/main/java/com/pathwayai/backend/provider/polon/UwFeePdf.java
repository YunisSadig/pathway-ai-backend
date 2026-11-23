package com.pathwayai.backend.provider.polon;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Reads the University of Warsaw tuition-fee PDF. The PDF is a table of merged cells: programme names sit in a left column,
 * each cycle (1st = bachelor-level, 2nd = master-level) has its own column with an EU/EFTA and a non-EU/EFTA sub-column, and
 * a cell is aligned vertically with the middle of the programme's name. So a row is reconstructed by POSITION, which is
 * inherently approximate. That is why this class only reports what it can attribute with every guard satisfied and why the
 * caller never publishes a result: each row, with its page and exact text, goes to a person for approval.
 */
final class UwFeePdf {

    /** One programme's row: the words assigned to it (page, exact text) and, only if every guard passed, the fee. */
    record Row(int page, String name, String text, Fee fee, String notStated) { }

    /** A non-EU/EFTA fee per year for one cycle, as printed. */
    record Fee(String amount, String currency, String printedAmount, String academicYear, int cycle) { }

    private record Word(String text, float x0, float x1, float y) {
        float centre() { return (x0 + x1) / 2; }
    }

    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+|\\d+");
    private static final Pattern YEAR = Pattern.compile("(20\\d\\d)/(20\\d\\d)");
    private static final float NAME_COLUMN_MAX_X = 230;
    private static final float SAME_BLOCK_GAP = 14;

    private UwFeePdf() { }

    /** The rows for the wanted programmes; a name that is absent or not unique gets a row stating so. {@code names} maps each printed programme name to its cycle (1 bachelor, 2 master). */
    static List<Row> read(byte[] pdf, java.util.Map<String, Integer> names) throws IOException {
        List<Row> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (var wanted : new TreeSet<>(names.keySet())) {
                List<Row> found = new ArrayList<>();
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    Row r = onPage(doc, p, wanted, names.get(wanted));
                    if (r != null) found.add(r);
                }
                if (found.size() == 1) out.add(found.get(0));
                else out.add(new Row(0, wanted, "", null, found.isEmpty() ? "programme name not found in the PDF"
                        : "programme name found more than once (" + found.size() + " pages): not attributable"));
            }
        }
        return out;
    }

    private static Row onPage(PDDocument doc, int page, String wanted, int cycle) throws IOException {
        List<Word> words = words(doc, page);
        // header: two "TUITION FEES PER YEAR" groups, one per cycle, on one line
        List<Word> header = words.stream().filter(w -> Math.abs(w.y() - headerY(words)) < 3
                && List.of("TUITION", "FEES", "PER", "YEAR").contains(w.text())).sorted(Comparator.comparing(Word::x0)).toList();
        boolean titled = pageText(words).contains("1ST CYCLE (BACHELOR-LEVEL)") && pageText(words).contains("2ND CYCLE (MASTER-LEVEL)");
        if (header.size() != 8 || !titled) return null;
        float[] centre = {centre(header.subList(0, 4)), centre(header.subList(4, 8))};
        TreeSet<String> years = new TreeSet<>();
        for (Word w : words) if (w.y() < 75) { Matcher m = YEAR.matcher(w.text()); while (m.find()) years.add(m.group()); }
        // programme-name blocks: upper-case words in the left column, consecutive lines grouped
        List<Word> nameWords = words.stream().filter(w -> w.y() > 90 && w.x0() < NAME_COLUMN_MAX_X && isName(w.text()))
                .sorted(Comparator.comparing(Word::y).thenComparing(Word::x0)).toList();
        List<List<Word>> blocks = new ArrayList<>();
        float lastLineY = -1000;
        for (Word w : nameWords) {
            if (blocks.isEmpty() || w.y() - lastLineY > SAME_BLOCK_GAP) blocks.add(new ArrayList<>());
            blocks.get(blocks.size() - 1).add(w);
            lastLineY = w.y();
        }
        int target = -1;
        for (int i = 0; i < blocks.size(); i++) {
            String n = blocks.get(i).stream().map(Word::text).reduce((a, b) -> a + " " + b).orElse("");
            if (n.equals(wanted)) { if (target >= 0) return new Row(page, wanted, "", null, "name appears twice on page " + page); target = i; }
        }
        if (target < 0) return null;
        // every other word belongs to the block whose vertical middle is nearest (cells are centred on the name)
        float[] mid = new float[blocks.size()];
        for (int i = 0; i < mid.length; i++) mid[i] = (float) blocks.get(i).stream().mapToDouble(Word::y).average().orElse(0);
        List<Word> mine = new ArrayList<>();
        for (Word w : words) {
            if (w.y() <= 90 || (w.x0() < NAME_COLUMN_MAX_X && isName(w.text()))) continue;
            int best = 0;
            for (int i = 1; i < mid.length; i++) if (Math.abs(mid[i] - w.y()) < Math.abs(mid[best] - w.y())) best = i;
            if (best == target) mine.add(w);
        }
        mine.sort(Comparator.comparing(Word::y).thenComparing(Word::x0));
        String text = rowText(wanted, mine);
        return new Row(page, wanted, text, fee(mine, centre, cycle, years, words, text), notStated(mine, centre, cycle, years, words, text));
    }

    private static boolean isName(String s) {
        return s.matches("[A-Z0-9,&:'\\-–/()]+") && !s.matches("\\d[\\d,]*") ;
    }

    private static float headerY(List<Word> words) {
        return words.stream().filter(w -> w.text().equals("TUITION") && w.y() > 60 && w.y() < 90).map(Word::y).findFirst().orElse(-1f);
    }

    private static float centre(List<Word> ws) {
        return (float) ((ws.stream().mapToDouble(Word::x0).min().orElse(0) + ws.stream().mapToDouble(Word::x1).max().orElse(0)) / 2);
    }

    private static String pageText(List<Word> words) {
        StringBuilder b = new StringBuilder();
        words.stream().filter(w -> w.y() < 60).sorted(Comparator.comparing(Word::y).thenComparing(Word::x0)).forEach(w -> b.append(w.text()).append(' '));
        return b.toString().replaceAll("\\s+", " ");
    }

    /** The assigned words, line by line (lines are words within 3 pt vertically), as printed. */
    private static String rowText(String name, List<Word> mine) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        float y = Float.NaN;
        for (Word w : mine) {
            if (!Float.isNaN(y) && Math.abs(w.y() - y) > 3) { lines.add(line.toString().trim()); line.setLength(0); }
            line.append(w.text()).append(' ');
            y = w.y();
        }
        if (line.length() > 0) lines.add(line.toString().trim());
        return name + " | " + String.join(" | ", lines);
    }

    private record Amount(Word number, Word currency, String digits) {
        float centre() { return (number.x0() + currency.x1()) / 2; }
    }

    private static List<Amount> amounts(List<Word> mine) {
        List<Amount> out = new ArrayList<>();
        for (int i = 0; i + 1 < mine.size(); i++) {
            Word a = mine.get(i), b = mine.get(i + 1);
            if (NUMBER.matcher(a.text()).matches() && (b.text().equals("EUR") || b.text().equals("PLN")) && Math.abs(a.y() - b.y()) < 3)
                out.add(new Amount(a, b, a.text()));
        }
        return out;
    }

    /** Why no fee is stated for this row, or null if one is. */
    private static String notStated(List<Word> mine, float[] centre, int cycle, TreeSet<String> years, List<Word> all, String text) {
        return decide(mine, centre, cycle, years, all, text).reason();
    }

    private static Fee fee(List<Word> mine, float[] centre, int cycle, TreeSet<String> years, List<Word> all, String text) {
        return decide(mine, centre, cycle, years, all, text).fee();
    }

    private record Decision(Fee fee, String reason) { }

    private static Decision decide(List<Word> mine, float[] centre, int cycle, TreeSet<String> years, List<Word> all, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (years.size() != 1) return no("the academic year is not stated exactly once in the page header");
        for (String stop : List.of("part-time", "extramural", "per semester", "long-cycle", "does not apply"))
            if (lower.contains(stop)) return no("the row carries a qualifier (\"" + stop + "\") that the amount's scope depends on");
        List<Amount> amounts = amounts(mine);
        if (amounts.isEmpty()) return no("no amount in the row");
        // the cycle column of each amount is the nearer header centre
        List<Amount> inCycle = amounts.stream().filter(a -> (Math.abs(a.centre() - centre[0]) <= Math.abs(a.centre() - centre[1]) ? 1 : 2) == cycle).toList();
        if (inCycle.size() != amounts.size()) return no("an amount lies in the other cycle's column: the row is not for this level alone");
        if (inCycle.size() != 1) return no(inCycle.size() + " amounts in the cycle column: not attributable");
        Amount a = inCycle.get(0);
        // category: only the non-EU/EFTA sub-column counts; it is known only from the printed sub-column header in the row
        Word nonEu = mine.stream().filter(w -> w.text().startsWith("Non-EU")).filter(w -> (Math.abs(w.centre() - centre[0]) <= Math.abs(w.centre() - centre[1]) ? 1 : 2) == cycle)
                .min(Comparator.comparing(Word::y).thenComparing(Word::x0)).orElse(null);
        if (nonEu == null) return no("the row prints no EU/EFTA versus non-EU/EFTA sub-column header, so the applicant category is not stated");
        // the sub-column starts at the "Citizens" word that opens its printed header, just left of "Non-EU/"
        float subStart = mine.stream().filter(w -> w.text().equals("Citizens") && Math.abs(w.y() - nonEu.y()) < 3 && w.x0() < nonEu.x0())
                .map(Word::x0).max(Float::compare).orElse(nonEu.x0());
        if (a.centre() < subStart) return no("the amount lies in the EU/EFTA sub-column, not the non-EU/EFTA one");
        if (!a.currency().text().equals("EUR") && !a.currency().text().equals("PLN")) return no("unsupported currency");
        String digits = a.digits().replace(",", "");
        String year = years.first();
        return new Decision(new Fee(digits, a.currency().text(), a.digits() + " " + a.currency().text(), year, cycle), null);
    }

    private static Decision no(String reason) {
        return new Decision(null, reason);
    }

    private static List<Word> words(PDDocument doc, int page) throws IOException {
        List<Word> out = new ArrayList<>();
        PDFTextStripper s = new PDFTextStripper() {
            @Override
            protected void writeString(String t, List<TextPosition> tp) {
                StringBuilder w = new StringBuilder();
                float x0 = 0, x1 = 0, y = 0;
                for (TextPosition p : tp) {
                    String u = p.getUnicode();
                    if (u.isBlank()) {
                        if (w.length() > 0) { out.add(new Word(w.toString(), x0, x1, y)); w.setLength(0); }
                        continue;
                    }
                    if (w.length() == 0) { x0 = p.getXDirAdj(); y = p.getYDirAdj(); }
                    x1 = p.getXDirAdj() + p.getWidthDirAdj();
                    w.append(u);
                }
                if (w.length() > 0) out.add(new Word(w.toString(), x0, x1, y));
            }
        };
        s.setSortByPosition(true);
        s.setStartPage(page);
        s.setEndPage(page);
        s.getText(doc);
        return out;
    }
}
