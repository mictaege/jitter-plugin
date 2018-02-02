package com.github.mictaege.jitter.plugin

import com.google.common.collect.HashBasedTable
import com.google.common.collect.Table

import static com.google.common.html.HtmlEscapers.htmlEscaper

class CriticalTermsViolations {

    FlavourCfg flavour
    Table<Integer, Integer, String> table

    CriticalTermsViolations(FlavourCfg flavour) {
        this.flavour = flavour
        table = HashBasedTable.create()
    }

    void add(FlavourCfg flavour, String pattern, File violated) {
        def rowIdx = table.rowKeySet().size()
        table.put(rowIdx, 0, flavour.name)
        table.put(rowIdx, 1, pattern)
        table.put(rowIdx, 2, violated.absolutePath)
    }

    boolean hasViolations() {
        return table.rowKeySet().size() > 0
    }

    void report(String path) {
        StringBuilder html = new StringBuilder()
        html.append("<!DOCTYPE html>\n")
        html.append("<html lang=\"en\">\n")
        html.append("<head>\n")
        html.append("<meta charset=\"UTF-8\">\n")
        html.append("<style>\n"
                + "* { font-family: Arial; }\n"
                + "body { padding: 32px; }\n"
                + "h1, h1 * { font-size: 24pt; }\n"
                + "p, td, th, li { font-size: 10pt; }\n"
                + "p, li { line-height: 140%; }\n"
                + "table {\n"
                + "  border-collapse: collapse;\n"
                + "  empty-cells: show;\n"
                + "  margin: 8px 0px 8px 0px;\n"
                +"}\n"
                + "th { background-color: #C3D9FF; }\n"
                + "th, td {\n"
                + "  border: 1px solid black;\n"
                + "  padding: 3px;\n"
                + "}\n"
                + "td { vertical-align: top; }\n"
                + "tr:nth-child(odd) { background-color: #ffffd0; }\n"
                + "li {\n"
                + "  margin-top: 6px;\n"
                + "  margin-bottom: 6px; \n"
                + "}\n"
                + "</style>\n")
        html.append("<title>Critical Terms Report</title>\n")
        html.append("</head>\n")
        html.append("<body>\n")
        html.append("<h1>Critical Terms Report</h1>\n")
        if (hasViolations()) {
            html.append("<h2>Critical terms violated!</h2>\n")
            html.append("<table>\n")
            html.append("<tr><th>Flavour</th><th>Critical Term</th><th>Violated Resource</th></tr>\n")
            table.rowKeySet().each { i ->
                def row = table.row(i)
                def flavour = htmlEscaper().escape(row.get(0))
                def pattern = htmlEscaper().escape(row.get(1))
                def violated = htmlEscaper().escape(row.get(2))
                html.append("<tr><td>$flavour</td><td>$pattern</td><td><a href=\"file:///$violated\">$violated</a></td></tr>\n")
            }
            html.append("</table>\n")
        } else {
            html.append("<h2>No critical terms violated.</h2>\n")
        }
        html.append("</body>\n")
        html.append("</html>")

        File reportFile = prepareFile(path)

        reportFile.text = html.toString()
    }

    private File prepareFile(String path) {
        def reportFolder = new File(path + "/reports/jitter")
        if (!reportFolder.exists()) {
            reportFolder.mkdirs()
        }
        def reportFile = new File(path + "/reports/jitter/CriticalTermsReport.html")
        if (!reportFile.exists()) {
            reportFile.createNewFile()
        }
        reportFile
    }

}