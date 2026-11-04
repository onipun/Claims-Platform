import { HttpErrorResponse } from '@angular/common/http';
import { ProblemError } from './problem-error';

describe('ProblemError', () => {
  it('parses a problem+json body with code, detail, errorId and field errors', () => {
    const err = new HttpErrorResponse({
      status: 400,
      error: {
        type: 'about:blank',
        title: 'Invalid request',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Check the highlighted fields.',
        errorId: 'e-123',
        errors: [
          { field: 'email', code: 'Email', message: 'must be a valid email' },
          { field: 'email', code: 'Size', message: 'second message' },
          { field: 'password', code: 'NotBlank', message: 'required' },
        ],
      },
    });
    const p = ProblemError.from(err);
    expect(p.status).toBe(400);
    expect(p.code).toBe('VALIDATION_FAILED');
    expect(p.title).toBe('Invalid request');
    expect(p.detail).toBe('Check the highlighted fields.');
    expect(p.errorId).toBe('e-123');
    expect(p.message).toBe('Check the highlighted fields.');
    expect(p.fieldMessage('email')).toBe('must be a valid email');
    expect(p.fieldMessage('nope')).toBeNull();
    expect(p.fieldMessages).toEqual({ email: 'must be a valid email', password: 'required' });
  });

  it('parses a JSON string body', () => {
    const p = ProblemError.fromResponse(409, '{"code":"STALE_VERSION","title":"Conflict"}');
    expect(p.code).toBe('STALE_VERSION');
    expect(p.status).toBe(409);
  });

  it('maps status 0 to NETWORK', () => {
    const p = ProblemError.from(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }));
    expect(p.code).toBe('NETWORK');
  });

  it('copes with a non-problem body such as a proxy HTML 502', () => {
    const p = ProblemError.from(new HttpErrorResponse({ status: 502, error: '<html>Bad Gateway</html>' }));
    expect(p.code).toBe('HTTP_502');
    expect(p.fieldErrors).toEqual([]);
  });

  it('wraps unknown throwables and passes ProblemError through', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const e = ProblemError.from(new Error('boom'));
    expect(e.code).toBe('CLIENT_ERROR');
    expect(e.message).toBe('boom');
    expect(console.error).toHaveBeenCalled();
    const p = new ProblemError({ status: 404, code: 'NOT_FOUND', title: 'Not found' });
    expect(ProblemError.from(p)).toBe(p);
  });
});
