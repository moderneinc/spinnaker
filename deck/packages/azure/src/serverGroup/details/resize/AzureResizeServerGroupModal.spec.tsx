import { screen } from '@testing-library/react';

import { ReactModal } from '@spinnaker/core';

import { AzureResizeServerGroupModal } from './AzureResizeServerGroupModal';

describe('AzureResizeServerGroupModal', () => {
  const runtimeServices: any = { serverGroupWriter: { resizeServerGroup: vi.fn() } };
  const application: any = { name: 'fnord', serverGroups: { refresh: vi.fn() } };
  const serverGroup: any = { name: 'azure-v001', account: 'test-account', region: 'westus', capacity: { desired: 3 } };

  afterEach(() => {
    ReactModal.dismissAll();
  });

  it('renders the resize form into the only modal layer', async () => {
    ReactModal.show(AzureResizeServerGroupModal, { application, serverGroup }, {}, runtimeServices);

    const targetSize = await screen.findByLabelText('Target Size');
    // A second full-screen `.modal` layer would cover this one and swallow every click.
    const modalLayers = Array.from(document.querySelectorAll('.modal'));

    expect(modalLayers).toHaveLength(1);
    expect(modalLayers[0]).toContainElement(targetSize);
    expect(targetSize).toHaveValue(3);
  });
});
