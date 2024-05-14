import { type StorageItem } from 'react-native-sync-provider';

const offlineData: StorageItem[] = [
  {
    data: [
      {
        guidIdMotorista: '7346aee8-62c6-452d-9084-9783f6a585dc',
        guidIdVeiculo: '2d3e4862-3a09-4eb7-9eb6-00c57c0eb80b',
        latitude: 61.640133,
        longitude: -148.234816,
        registered: '2015-11-01',
      },
      {
        guidIdMotorista: '4bff8a38-2353-4758-8568-b5d21f3ce00e',
        guidIdVeiculo: '3b68c86d-b854-48bd-9776-fb957451f131',
        latitude: 83.860506,
        longitude: 10.335275,
        registered: '2016-05-10',
      },
    ],
    urlEndpoint: 'Endpoint to Upload data',
  },
];

export default offlineData;
