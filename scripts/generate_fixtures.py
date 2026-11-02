#!/usr/bin/env python3
"""Create real two-page/slide files for local API and browser verification."""
from pathlib import Path
import argparse
from reportlab.pdfgen.canvas import Canvas
from docx import Document
from pptx import Presentation
from pptx.util import Inches

parser = argparse.ArgumentParser()
parser.add_argument('--output', default='.run/fixtures')
args = parser.parse_args()
root = Path(args.output)
root.mkdir(parents=True, exist_ok=True)
canvas = Canvas(str(root / 'sample.pdf'))
for number in (1, 2):
    canvas.setFont('Helvetica', 22)
    canvas.drawString(72, 740, f'CUHKCampus PDF page {number}')
    canvas.setFont('Helvetica', 12)
    canvas.drawString(72, 700, f'VERIFY_PDF_PAGE_{number}')
    canvas.showPage()
canvas.save()
doc = Document()
for number in (1, 2):
    if number == 2:
        doc.add_page_break()
    doc.add_heading(f'CUHKCampus Word page {number}', 0)
    doc.add_paragraph(f'VERIFY_DOCX_PAGE_{number}')
    doc.add_paragraph('Course materials preserve headings and paragraphs in the PDF preview.')
doc.save(root / 'sample.docx')
presentation = Presentation()
for number in (1, 2):
    slide = presentation.slides.add_slide(presentation.slide_layouts[1])
    slide.shapes.title.text = f'CUHKCampus slide {number}'
    slide.placeholders[1].text = f'VERIFY_PPTX_SLIDE_{number}\nOriginal presentation layout'
presentation.save(root / 'sample.pptx')
(root / 'notes.txt').write_text('VERIFY_SOURCE_TEXT\nThis is a real uploaded study document.\n', encoding='utf-8')
print(f'Created PDF(2 pages), DOCX(2 pages), PPTX(2 slides), TXT in {root}')
