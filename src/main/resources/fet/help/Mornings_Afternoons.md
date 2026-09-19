Mode: mornings-afternoons. This mode assumes that the number of FET days is the double of the real number of days per week, such that the first FET day is the morning of the first real day, the second FET day is the afternoon of the first real day, the third FET day is the morning of the second real day, and so on. This mode was developed with suggestions from the users of FET from Morocco and Algeria. It may be used in other countries as well.

## Unrestricted teachers (Algeria)

This help by Liviu Lalescu, last modified on 2 July 2025.

FET mornings-afternoons with unrestricted mornings/afternoons for teachers was originally designed for Algerian schools (as requested by the user aissa), but it can be used in other institutions working in two shifts where teachers can work both in the morning and in the evening on the same day.

Please define days in FET to be double of the real days, that is if your week has 5 days, define 10 FET days. First day is for morning, second day is for afternoon, and so on.

Very important advice about the constraint min days between activities: probably you will need to add min days = 1 for all constraints. I modified the source code and min 1 day means that the activities must be on different real days, so it cannot be that one activity is in the morning and another is in the afternoon. If you need constraint to be always respected, please use 100% weight. If you allow weight under 100% and select consecutive if on the same day, then activities must be either in the morning or exclusively in the afternoon.

Min 1 day means that the activities cannot be in same REAL day (so they can be on Monday afternoon and Tuesday morning, but not both on Monday).

Min 2 days means that the activities must be 2 REAL days apart (so they can be on Monday afternoon and Wednesday morning, but not on Monday and Tuesday).

If your data is too difficult (impossible), maybe you can deactivate consecutive if on the same day for all constraints min days between activities (from the modify multiple constraints at once dialog, which can be opened from the constraints min days between activities dialog).

Constraint max days between activities considers real days.

The constraint students (set) min hours daily without allowing empty days is for real days, and for nonempty FET days (half days) it respects the minimum number of hours daily.

HelpForMorningsAfternoonsThis question and answer by bachiri401 and Liviu Lalescu:bachiri401 and Liviu Lalescu are two persons

HelpForMorningsAfternoonsbachiri401: In Algeria, we can not teach the same subject in the same real day. For example a subject divided into 3 activities (1+1+1) must be on three different real days. The constraint min days between activities here is great (working for real days).bachiri401 is the name of the person writing this paragraph

HelpForMorningsAfternoonsBut for example we have students studying math and sport. We want math and sport not to be on the same half day but they can be on the same real day.

HelpForMorningsAfternoonsbachiri401 also said that for this problem he uses a trick, adding a constraint min gaps between the activities.bachiri401 is the name of a person

HelpForMorningsAfternoonsAnd indeed, Liviu Lalescu says this: I checked the code. There is no need for a new constraint. Please use a constraint min gaps between activities with min gaps = the number of hours per half day (the maximum FET allows). Add all the math and sport activities for a students set for your example. It is implemented efficiently for this case.Liviu Lalescu is the name of a person

HelpForMorningsAfternoonsNote: in FET version 6.4.0, suggested by ngoctp29121982, it was added a new type of constraint probably useful in this case: min half days between activities, so that the trick above is now no longer necessary.6.4.0 is the FET version number, ngoctp29121982 is a person

## Exclusive teachers (Morocco)

This help by Chafik Graiguer and Liviu Lalescu, last modified on 2 July 2025.

FET mornings-afternoons with exclusive mornings/afternoons for teachers was designed for institutions in Morocco, but might be also used in other institutions working in two shifts, where teachers can work either in the morning or in the evening, but not both on the same day (with exceptions).

--------------------------------------

This is a version specially made for Preparatory and Secondary schools in Morocco; in other words, it is suitable to 'colleges' and 'lyceums' in Morocco. It was requested by the user Chafik Graiguer.

These schools have a morning shift and an afternoon shift.

--------------------------------------

These are the words of user Chafik Graiguer:

This FET version fulfills the following requirements:

1 - Definition of a working school day:

	A day is divided into two distinct periods:

	- morning 08:00 - 12:00
	- afternoon 14:00 - 18:00
	- there is a lunch break 12:00 - 14:00

2 - Studying periods and gaps per day:

	- Students can have gaps around lunch break, i.e. before or after lunch break (official FET version cannot tolerate this !!!)
	- Students and teachers must have at least 2 hours per period (Empty periods are OK.)
	- Teachers can only have activities either in the morning or in the afternoon. Never both.

3 - The key hint to use this version

We have 6 REAL days, with 8 working time slots

We should input 6x2 = 12 days, with 4 working time slots ONLY

--------------------------------------

Of course you can input less or more hours in each day and less or more days per week, as you need.

Features:

Intelligent min hours daily for students (can have days with 0 hours).

User must input an even number of days per week. The first FET day is morning real day 1, the second FET day is afternoon real day 1, and so on (FET days are double than real days).

A teacher can have hours in first day or second day, but not both. Same for third and fourth, fifth and sixth, and so on. (in fact, there are 2*normal days, first is morning, second is afternoon).

Exceptions for teachers: they allow for some teachers to work in double morning+afternoon for a single day (1 exception) or for 2 days (2 exception). (Recently, it is also possible to allow 3, 4, or 5 days exceptions.)

Very important advice about the constraints of type min days between activities: probably you will need to add min days = 1 for all the constraints. I modified the source code and min 1 day means that the activities must be on different real days, so it cannot be that one activity is in the morning and another is in the afternoon. If you need the constraint to be always respected, please use 100% weight. If you allow weight under 100% and select consecutive if on the same day, then the activities must be either in the morning or exclusively in the afternoon.

Min 1 day means that the activities cannot be in same REAL day (so they can be on Monday afternoon and Tuesday morning, but not both on Monday).

Min 2 days means that the activities must be 2 REAL days apart (so they can be on Monday afternoon and Wednesday morning, but not on Monday and Tuesday).

If your data is too difficult (impossible), maybe you can deactivate consecutive if on the same day for all constraints min days between activities (from the modify multiple constraints at once dialog, which can be opened from the constraints min days between activities dialog).

Constraint max days between activities considers real days.

The constraint students (set) min hours daily without allowing empty days is for real days, and for nonempty FET days (half days) it respects the minimum number of hours daily.

HelpForMorningsAfternoonsThis question and answer by bachiri401 and Liviu Lalescu:bachiri401 and Liviu Lalescu are two persons

HelpForMorningsAfternoonsbachiri401: In Algeria, we can not teach the same subject in the same real day. For example a subject divided into 3 activities (1+1+1) must be on three different real days. The constraint min days between activities here is great (working for real days).bachiri401 is the name of the person writing this paragraph

HelpForMorningsAfternoonsBut for example we have students studying math and sport. We want math and sport not to be on the same half day but they can be on the same real day.

HelpForMorningsAfternoonsbachiri401 also said that for this problem he uses a trick, adding a constraint min gaps between the activities.bachiri401 is the name of a person

HelpForMorningsAfternoonsAnd indeed, Liviu Lalescu says this: I checked the code. There is no need for a new constraint. Please use a constraint min gaps between activities with min gaps = the number of hours per half day (the maximum FET allows). Add all the math and sport activities for a students set for your example. It is implemented efficiently for this case.Liviu Lalescu is the name of a person

HelpForMorningsAfternoonsNote: in FET version 6.4.0, suggested by ngoctp29121982, it was added a new type of constraint probably useful in this case: min half days between activities, so that the trick above is now no longer necessary.6.4.0 is the FET version number, ngoctp29121982 is a person
