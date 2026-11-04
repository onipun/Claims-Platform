-- Staff review of an uploaded document. The note is shown to the claimant when a document is re-requested.
ALTER TABLE claim_document
  ADD COLUMN review_note text,
  ADD COLUMN reviewed_by uuid REFERENCES app_user (id),
  ADD COLUMN reviewed_at timestamptz;
