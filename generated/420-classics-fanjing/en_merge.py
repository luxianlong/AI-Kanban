# -*- coding: utf-8 -*-
import json
D = json.load(open('en-part1.json'))
for f in ['en-part2.json','en-part3.json','en-part4.json','en-part5.json','en-part6.json']:
    p = json.load(open(f))
    D['juan'].update(p.get('juan', {}))
    D['chapters'].update(p.get('chapters', {}))

D['colophon'] = [
 '<b>What this reading is.</b> The Chinese text on the other two readings of this page runs to 226,928 characters — about 183,800 without the punctuation — of which 56 per cent is Zhao Rui’s own running commentary, itself made almost entirely of quotations from the histories and the philosophers. This English reading covers <b>all sixty-four chapters and the preface</b>, and for each of them it states the argument, translates the sentences the chapter turns on, and names and summarises the historical cases the author heaps up underneath. <b>It is a reading, not a sentence-for-sentence translation of all 226,928 characters.</b> Where a passage is given between quotation marks it is translated; where it is described, it is described. The complete text is on the 繁體 and 简体 readings, and every chapter here is numbered and titled so that a reader can find the Chinese for any paragraph.',
 '<b>The book.</b> Its own name is the <i>Changduanjing</i>, the Classic of the Long and the Short, finished in 716 by Zhao Rui of Yanting in Zizhou, who signed himself “a commoner of the grass” and did not go when the court summoned him. Sun Guangxian’s <i>Beimeng Suoyan</i> records that he and his wife both lived in retirement; the New History of the Tang gives his courtesy name as Taibin and calls the book the <i>Changduan Yaoshu</i> — one book with two names. That is the whole of what is known about him. The title <i>Fanjing</i>, “the Classic of Reversal,” by which the book is now generally known in Chinese, is later: it is the title of the thirteenth chapter, the one that takes the eight instruments of government and turns each of them over to show the harm it does.',
 '<b>The text.</b> The base is the punctuated text of the Chinese Wikisource, collated character by character against the same site’s transcription of the <i>Siku Quanshu</i> copy — which is unpunctuated but marks the author’s double-column notes explicitly, and carries the Qianlong emperor’s verses, the Siku editors’ notice, and a colophon of 1377 by Shen Xinmin that the editors suspected a bookseller of forging. The collation produced 2,085 differences of glyph, 118 places where the Siku copy is illegible, and <b>2,585 substantive variants over 6,834 character positions</b>.',
 '<b>Two repairs worth stating in English.</b> First, the base text had Scroll Four and Scroll Five welded together and out of order: the whole of Scroll Five (“The Seven Powers in Outline”) had been dropped into the middle of the first note of Scroll Four, and a separate page carried a truncated duplicate of Scroll Four’s opening. The Siku copy divides them cleanly, and so does the Qing printing whose opening leaf is the cover of this page; they have been separated again here. Second, five passages missing from the base text have been supplied from the Siku copy, some 3,300 characters in all — the largest of them the whole second half of chapter 15, “Fitting the Change,” which contains the assessments of the Huang-Lao, Confucian, Mohist and Legalist methods and without which that chapter has no conclusion.',
 '<b>The cover</b> is this book’s own opening leaf: the National Diet Library’s copy of the <i>Duhuazhai congshu</i> printing of the <i>Changduanjing</i>, edited by Gu Xiu of Tongxiang in the Jiaqing period (shelfmark 082.1-D95K), scroll one, first leaf. It carries the scroll title, the general heading “Civil Matters, Upper,” the list of the eight chapters in the scroll, the chapter title “The Large Frame,” and the first sentence of the book.',
 'The chapter introductions and the framing prose on this page were written by an AI; the translated sentences are translations, and the historical statements have been checked against the text itself.',
]
json.dump(D, open('en.json','w'), ensure_ascii=False, indent=1)
need = ['xu'] + ['c%d' % i for i in range(1, 65)]
missing = [k for k in need if k not in D['chapters']]
mj = [k for k in ['j%d' % i for i in range(1, 10)] if k not in D['juan']]
print('chapters', len(D['chapters']), 'juan', len(D['juan']), 'missing', missing, mj)
words = sum(len(' '.join(v['paras']).split()) for v in D['chapters'].values())
print('~words', words)
