# -*- coding: utf-8 -*-
"""Colophon and UI labels for the English reading."""

LABELS = {'preface': 'Contents', 'colophon': 'Text and Collation'}

COLO = [
 '<b>Base text.</b> The <i>Siku quanshu</i> Wenyuan Ge text of the <i>Weiliaozi</i>, five scrolls '
 'and twenty-four chapters, 9,216 characters, without punctuation. <b>The punctuation and the '
 'paragraphing on this page are ours, not the base text’s.</b> For the other six of the Seven '
 'Military Classics a usable punctuated text can be found online; for this one it cannot. The '
 'Chinese Wikisource punctuated <i>Weiliaozi</i> has four chapters entirely empty — the orders on '
 'the squad system, on sectors, on binding the squads and on ordering the troops — while '
 '<i>Martial Authority</i> runs to only 277 characters (under two-fifths of the chapter) and '
 '<i>Orders for the Army, Part Two</i> to fifty-five; the whole comes to less than half the '
 'received text, and the first chapter is moreover entered from the Tang anthology rather than '
 'from the same recension as the rest. Such a text cannot serve as a base, so we took the '
 '<i>Siku</i> text and supplied the punctuation ourselves. One thing is checked by machine on '
 'every rebuild: <b>strip our punctuation and paragraph breaks and the text must be '
 'character-for-character identical with the <i>Siku</i> text</b>. One character out and the '
 'build stops.',

 '<b>Two transcriptions.</b> The <i>Siku</i> text was taken in two mutually independent '
 'transcriptions: the WYG branch of Kanripo KR3b0006, and the Chinese Wikisource '
 '<i>Weiliaozi (Siku quanshu edition)</i>. <b>Where two transcriptions disagree, that is an error '
 'of transcription and not a variant of the edition.</b> Here the two agree over all 9,216 '
 'characters — zero discrepancies. Zero does not by itself prove independence, since copying one '
 'from the other would give zero too; but the characters the two mark as unavailable are not the '
 'same set (Wikisource flags 揚 and 龜, which Kanripo simply writes; both flag 忘 and 樕), and that '
 'difference of judgement shows neither was copied from the other.',

 '<b>Inspection of a printed edition.</b> Key places were checked character by character against '
 'an edition outside the <i>Siku</i> line and without commentary: <b>the plain-text woodblock '
 '<i>Weiliaozi</i> in the Shanghai Library</b> (Wikimedia Commons, PD-scan; eight columns to the '
 'half-leaf, twenty characters to the column, single border, two vermilion seals at the head '
 'of the first scroll, the lower reading “Collection of the Shanghai Library”; the '
 '<i>Huang Shigong sanlüe</i> follows in the same volume). Our cover image is its opening leaf. '
 'It is not a fine edition: in chapter three, where the base text has “one man with a sword strikes '
 'out in the market,” it has two graphic errors in a single clause. Its readings are therefore used '
 'as third-party corroboration and are not followed on their own.',

 '<b>The oldest witness: the <i>Qunshu zhiyao</i>.</b> The <i>Essentials of Government from the '
 'Assembled Books</i> was compiled by Wei Zheng and others on imperial order in 631. Its '
 'thirty-seventh scroll excerpts four chapters of the <i>Weiliaozi</i> — <i>The Offices of '
 'Heaven</i>, <i>Discussing War</i>, <i>Martial Authority</i> and <i>Orders for the Army</i> — '
 '1,431 characters in all. The work was lost early in China and survived in Japan through the '
 'Kanazawa Bunko manuscript; the domain of Owari printed it in the Tenmei era, and the '
 '<i>Sibu congkan</i> reproduced the Hanfenlou copy of that printing. <b>It is more than four '
 'hundred years older than any surviving edition of the <i>Weiliaozi</i> itself.</b> Collated '
 'character by character against the received text, the agreement is 59 per cent for '
 '<i>The Offices of Heaven</i>, 42 for <i>Discussing War</i>, 66 for <i>Martial Authority</i> and '
 '36 for <i>Orders for the Army</i>, with 115 differences of two characters or more. At that '
 'distance it is not a set of variants but a different recension, and <b>we have not moved its '
 'readings into the text</b>: picking sentences out of it and fitting them into the received text '
 'would be rewriting, not editing. Every difference is recorded in the note to the paragraph '
 'concerned.',

 '<b>Four things the Tang anthology keeps and the received text has lost.</b> (1) In <i>The Offices '
 'of Heaven</i> it reads “with twelve thousand men struck Zhòu’s hundred and eighty thousand, '
 '<b>cut off Zhòu’s head and hung it from the white banner</b>” where the received text has only '
 '“and destroyed the Shang” — and “cut off Zhòu’s head and hung it from the great white banner” is '
 'the wording of the <i>Records of the Grand Historian</i>. (2) In <i>Martial Authority</i>, after '
 '“the rule for giving orders is: do not revise for a small mistake, do not intervene for a small '
 'doubt,” the anthology has a matching section on <i>affairs</i> that the received text lacks '
 'entirely. The original had two parallel paragraphs, one on orders and one on affairs, with the '
 'same opening and closing words; a copyist’s eye skipped from the one to the other. <b>The trace '
 'of the loss is still legible.</b> (3) Also in <i>Martial Authority</i>, the anthology reads “he '
 'must root it in spirit so as to steel the will, steel the will so as to move the four limbs, move '
 'the four limbs so as to wield the five weapons” — spirit, will, limbs, weapons, a chain of four; '
 'the received text has lost the chain. (4) Under <i>Orders for the Army</i> the anthology has a '
 'whole passage absent from the received text: a general has for his men no parent’s pity, no tie '
 'of blood, no private bond of kin, and yet they go at the enemy as though going home, because “in '
 'front they see a reward made wholly plain, and behind them a punishment that is certain death.” '
 'It is the book’s plainest answer to the question of what drives men to their deaths, and it is '
 'gone.',

 '<b>Two emendations.</b> The rule requires three conditions at once: the Shanghai printed edition '
 'differs from the base text; the base text will not construe; and the Tang anthology supports the '
 'other reading. Two places satisfy it. (1) In <i>The Offices of Heaven</i>, “the Yellow Emperor’s '
 '<b>xíng</b> 形 and virtue” becomes “<b>xíng</b> 刑 and virtue,” punishment and virtue. The printed '
 'edition has 刑 — it is visible in the third column of our cover image — and the anthology reads '
 '“I have heard that the Yellow Emperor had punishment and virtue.” The reply, which distinguishes '
 'punishing from holding, requires the question to have asked about punishment. (2) In '
 '<i>Martial Authority</i>, “the man who fights <b>xīn</b> 心 roots himself in self-discipline” '
 'becomes “<b>bì</b> 必 must root himself.” The printed edition has 必 — the same glyph as in “must '
 'put ritual and good faith first” in the neighbouring column of the same leaf — and the anthology '
 'reads “must root it in spirit.” The received reading does not construe, and 心 recurs two clauses '
 'later in “as the mind moves the four limbs.”',

 '<b>Readings not emended.</b> Where the witnesses differ but the base text reads smoothly we have '
 'left it alone and recorded the variant in the note. Among them: <i>Discussing Regulations</i> '
 '“every movement strikes the <b>advantage</b>” (editors since the Ming have preferred “strikes the '
 '<b>measure</b>,” but the <i>Siku</i> text and the printed edition both have “advantage”); “carry '
 'on our <b>spirit</b>” (other editions “carry on after us”); <i>Orders on Binding the Squads</i> '
 '“a company officer may execute <b>ten</b> commanders” (usually read as “section commanders”); and '
 '<i>Deliberations on the Martial</i> “Zhòu had <b>subjects</b> by the hundred million” — not a '
 'corruption of “battle line,” since the <i>Book of Documents</i> has exactly “Shou had subjects by '
 'the hundred million, and a hundred million minds.”',

 '<b>The division into five scrolls is not the old one.</b> The Qing imperial catalogue says: “The '
 'shop editions of this book have no scroll divisions; we have accordingly divided it into five '
 'scrolls following the Sui bibliography, roughly preserving the old arrangement.” <b>The first half '
 'of that statement is not true.</b> The Shanghai Library copy is exactly such a shop-printed '
 'plain-text edition, and its opening leaf is headed “Weiliaozi, scroll one”; “scroll two,” “scroll '
 'three,” “scroll four” and “scroll five” all appear in their places, each beginning a fresh leaf '
 'with the verso of the preceding leaf left blank. We have examined the scans of all four interior '
 'scroll-heads. Moreover <b>its five scrolls are not divided where the <i>Siku</i>’s are</b>: in the '
 'printed edition scroll two begins at chapter five, <i>The Attacker’s Leverage</i>, and scroll three '
 'at chapter ten, <i>The Origins of Office</i>; in the <i>Siku</i> text they begin at chapters six and '
 'eleven — one chapter later in each case. Scrolls four and five begin at the same place in both. '
 'This page follows the <i>Siku</i> divisions because the text is the <i>Siku</i> text; the printed '
 'edition’s divisions are recorded here. (The same thing happened in the previous round: the '
 'catalogue says the <i>Questions and Replies</i> circulates without scroll divisions, and the '
 'Shanghai Library’s plain-text edition of that book divides it plainly.)',

 '<b>A slip of arithmetic in the catalogue.</b> The Qing catalogue says “the Han bibliography lists '
 'among the eclectics a <i>Wei Liao</i> in thirty-nine chapters.” The received text of the '
 '<i>Han shu</i> bibliography has <b>a <i>Weiliaozi</i> in twenty-nine chapters</b> among the '
 'eclectics, and separately a <i>Wei Liao</i> in thirty-one among the writers on configuration and '
 'momentum. “Thirty-nine” is a mistake for “twenty-nine.” The argument the catalogue goes on to make '
 '— quoting Hu Yinglin that the military <i>Wei Liao</i> is the book we have and the eclectic one is '
 'not, and inferring from “twenty-four chapters today against thirty-one then” that some have been '
 'lost — is unaffected by the slip.',

 '<b>Authenticity and the bamboo slips.</b> From the Song onwards many took this book for a forgery, '
 'on grounds no better than that its diction is shallow and that a man addressed by King Hui of Liang '
 'is called “Weiliaozi.” In 1972 tomb no. 1 at Yinqueshan in Linyi, Shandong, yielded bamboo slips '
 'including fragments corresponding to the present <i>Discussing War</i>, <i>The Attacker’s '
 'Leverage</i>, <i>The Defender’s Leverage</i>, <i>The General as Magistrate</i>, <i>The Origins of '
 'Office</i> and <i>Orders for the Army</i>. The burial dates from between the reigns of Wen and Wu '
 'of the Western Han; the slip text differs from the received text in many places but the chapter '
 'titles and wording descend in the same line. <b>The book was already circulating in something close '
 'to its present shape in the early Western Han, and is not a Tang or Song fabrication.</b> We have '
 'not examined the slips or their published plates; this paragraph reports published scholarship and '
 'is of a different order from the collations above, which is why it is set apart.',

 '<b>Paragraphing.</b> The book’s own divisions are five scrolls and twenty-four chapters, and there '
 'is nothing below the chapter — no sections, no subsections, no headings — <b>and we have not '
 'supplied any</b>. The paragraphs, 140 of them, are ours, cut for reading and for annotation, and '
 'that is stated here. The contents list on the left and the contents drawer on small screens show '
 'only the five scrolls, the twenty-four chapters and this section: the book’s own levels and no '
 'others.',

 '<b>This reading.</b> All 140 paragraphs are translated in full; nothing is summarised and nothing '
 'is passed over. Chapter titles are rendered rather than transliterated, with the Chinese given '
 'beside them. Chinese and Japanese readings of the same text are linked at the top of the page, and '
 'the Chinese pages carry a paragraph-by-paragraph commentary that can be switched on and off.',

 '<b>Cover.</b> The opening leaf of scroll one of the Shanghai Library plain-text woodblock '
 '<i>Weiliaozi</i> (as above, PD-scan). Reading right to left the leaf gives “Weiliaozi, scroll one,” '
 '“The Offices of Heaven, chapter one,” and the first sentence of the book — “King Hui of Liang asked '
 'Wei Liao: the Yellow Emperor had punishment and virtue, and with them a hundred victories; is it '
 'so?” — with two vermilion seals below the title. <b>The first character we emended is on this '
 'leaf</b>: the printed edition has 刑, punishment, where the <i>Siku</i> text has 形.',
]
