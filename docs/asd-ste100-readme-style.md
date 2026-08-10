# ASD-STE100 style note for the README

## Source and scope

The current release is **ASD-STE100 Issue 9, dated 2025-01-15**. It is an international standard for technical documentation. It contains 53 writing rules in nine sections and a controlled dictionary. The dictionary has approximately 900 approved words. In general, it gives one meaning and one part of speech to each approved word.

Official sources:

- [ASD-STE100 home page](https://www.asd-ste100.org/)
- [About ASD-STE100 and Issue 9](https://www.asd-ste100.org/about_STE.html)
- [Official Issue 9 PDF](https://www.asd-ste100.org/assets/files/ASD-STE100_ISSUE9.pdf)
- [Official FAQ](https://www.asd-ste100.org/STE_faq.html)
- [Official guidance about STE tools](https://www.asd-ste100.org/STEsoftware.html)

STE was made for technical documentation, not for general-purpose writing. The official FAQ says that writers can apply principles such as short sentences, one topic in each sentence, and active voice in other contexts. Thus, these rules are useful for this software README, but the README is not the standard's original document type.

## Practical rules for this README

Use this profile when you rewrite the README:

1. **Use short sentences.** Use no more than 20 words in an instruction. Use no more than 25 words in descriptive text. Keep one topic in each sentence. Keep one topic in each paragraph and use no more than six sentences in a paragraph. See Issue 9, rules 5.1, 6.3, 6.5, and 6.6.
2. **Use one instruction in each sentence.** Use more than one action only when the actions occur at the same time. Write instructions in the command form. If the reader must know a condition first, put the condition before the command. See rules 5.2 through 5.4.
3. **Use active voice.** Identify the person or thing that does the action. In descriptive text, use passive voice only when the agent is unknown. See rule 3.6.
4. **Control the vocabulary.** Use approved dictionary words with their approved meaning and part of speech. Use the same term for the same item. Do not alternate between synonyms only for variety. See rules 1.1 through 1.4 and 1.11.
5. **Define software terms as technical terms.** Terms such as `Android`, `APK`, `SMS`, `SIM`, `eSIM`, `Gradle`, and `ADB` can be technical nouns. Use the official product or project term. Keep each term consistent. STE permits subject-field technical nouns and technical verbs. See rules 1.5 through 1.13.
6. **Keep noun groups short.** A multi-word noun usually has no more than three words. If an official technical noun is longer, write it in full first. Then define and use a clear short form. See rules 2.1 and 2.2.
7. **Use articles.** When applicable, put `a`, `an`, `the`, `this`, or `these` before a noun or multi-word noun. Do not remove an article only to make text shorter. See rule 4.5.
8. **Make lists direct.** Use a vertical list when a sentence becomes complex. Keep the grammar of list items parallel. Do not mix instructions and descriptions in one list. See rules 4.3 and 8.4.
9. **Write safety text as safety text.** Select a label that identifies the risk level and type. Start with a clear command or condition. Then explain the risk or possible result. In the standard, a warning concerns injury or death, and a caution concerns damage. For a software privacy or delivery risk, use `Caution` and name the risk type. The 20-word instruction limit also applies to safety instructions. See rules 5.1 and 7.1 through 7.3.
10. **Use American English.** Use American spelling unless an applicable official directive requires different spelling. See rule 1.14.

## Terminology for this project

Create a small project glossary before the final edit. For each concept, select one term and use it everywhere. For example:

| Concept | Preferred term | Do not alternate with |
| --- | --- | --- |
| Application | app | application, program, tool |
| Installation file | APK | package, binary, installer |
| Receiving line | inbound SIM | source SIM, input SIM |
| Sending line | outbound SIM | forwarding SIM, output SIM |
| Target number | destination number | recipient, forwarding target |
| Feature state | forwarding is on/off | active/inactive, enabled/disabled |

The final glossary must agree with the product UI, specification, and Android terminology. If those sources use a different official term, use that official term.

## How to describe the result

Use **“written with ASD-STE100 principles”** or **“adapted from ASD-STE100 Issue 9”** unless a qualified reviewer checks the complete README against the full Issue 9 rules, dictionary, and project terminology.

This limitation is a practical inference from the official sources. The standard requires both its writing rules and controlled dictionary. The official tools page also explains that automated checkers cannot test all rules, can give incorrect results, and require a maintained technical-term glossary. A short-sentence check alone does not establish full conformance.

Do not copy or redistribute the standard. ASD owns its copyright and trademark. Link to the official website or official PDF instead.
