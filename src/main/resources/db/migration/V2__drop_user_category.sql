-- The consumer category (GENERAL, STUDENT, SENIOR, IN_NEED) was never read by the application:
-- free claims depend only on eligibility_status. Storing why someone is "in need" also went
-- against the rule of keeping only the outcome of the verification, so the column is removed.
alter table app_user drop column category;
